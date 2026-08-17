package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.AiAttendanceReminderBatch;
import com.indraacademy.ias_management.entity.AiFeeReminderBatch;
import com.indraacademy.ias_management.entity.AiReminderBatch;
import com.indraacademy.ias_management.entity.AiTeacherAttendanceReminderBatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Covers the batch lifecycle shared by all three reminder workflows.
 *
 * <p>The gap this closes: approval and rejection were asymmetric. A send wrote a terminal status
 * to Postgres via dispatch(), but a rejection lived only in the LangGraph checkpoint in Redis, so
 * Spring's durable row read PENDING_APPROVAL forever — indistinguishable from a batch nobody ever
 * acted on, and lost entirely once the checkpoint expired.
 */
@ExtendWith(MockitoExtension.class)
class AiReminderBatchServiceTest {

    @Mock private JpaRepository<AiFeeReminderBatch, Long> feeRepo;

    private final AiReminderBatchService service = new AiReminderBatchService();

    private AiFeeReminderBatch pending() {
        AiFeeReminderBatch b = new AiFeeReminderBatch();
        b.setWorkflowId("wf-1");
        b.setStatus(AiReminderBatchService.PENDING_APPROVAL);
        return b;
    }

    @Test
    void rejectingAPendingBatch_persistsRejectedAndStampsCompletedAt() {
        AiFeeReminderBatch batch = pending();

        boolean changed = service.markRejected(() -> Optional.of(batch), feeRepo);

        assertThat(changed).isTrue();
        assertThat(batch.getStatus()).isEqualTo("REJECTED");
        assertThat(batch.getCompletedAt()).isNotNull();  // a rejection is a completion, not a limbo
        verify(feeRepo).save(batch);
    }

    @Test
    void rejectingTwice_isANoOp_theSecondTimeAndNeverRewritesTheRow() {
        AiFeeReminderBatch batch = pending();
        service.markRejected(() -> Optional.of(batch), feeRepo);
        var firstCompletedAt = batch.getCompletedAt();
        reset(feeRepo);

        boolean changed = service.markRejected(() -> Optional.of(batch), feeRepo);

        assertThat(changed).isFalse();
        assertThat(batch.getCompletedAt()).isEqualTo(firstCompletedAt);
        verify(feeRepo, never()).save(any());
    }

    /**
     * The ordering that actually matters: emails have already gone out. A rejection arriving
     * afterwards (a retry, a double-click, a delayed proxy call) must not overwrite the send's
     * recorded outcome and make the audit trail claim nothing was sent.
     */
    @Test
    void rejectionArrivingAfterASend_neverOverwritesTheSendOutcome() {
        AiFeeReminderBatch batch = pending();
        batch.setStatus(AiReminderBatchService.SENT);
        batch.setSentCount(7);

        boolean changed = service.markRejected(() -> Optional.of(batch), feeRepo);

        assertThat(changed).isFalse();
        assertThat(batch.getStatus()).isEqualTo("SENT");
        assertThat(batch.getSentCount()).isEqualTo(7);
        verify(feeRepo, never()).save(any());
    }

    @Test
    void partiallySentAndFailedAreAlsoProtectedFromALateRejection() {
        for (String terminal : new String[]{AiReminderBatchService.PARTIALLY_SENT, AiReminderBatchService.FAILED}) {
            AiFeeReminderBatch batch = pending();
            batch.setStatus(terminal);

            assertThat(service.markRejected(() -> Optional.of(batch), feeRepo)).isFalse();
            assertThat(batch.getStatus()).isEqualTo(terminal);
        }
        verify(feeRepo, never()).save(any());
    }

    @Test
    void missingBatchRow_isReportedNotThrown_theRejectionItselfStillSucceeded() {
        boolean changed = service.markRejected(Optional::empty, feeRepo);

        assertThat(changed).isFalse();
        verify(feeRepo, never()).save(any());
    }

    @Test
    void rejectedCountsAsTerminal_soDispatchAndApprovalCanBothRefuseIt() {
        assertThat(AiReminderBatchService.isTerminal("REJECTED")).isTrue();
        assertThat(AiReminderBatchService.isTerminal("SENT")).isTrue();
        assertThat(AiReminderBatchService.isTerminal("PARTIALLY_SENT")).isTrue();
        assertThat(AiReminderBatchService.isTerminal("FAILED")).isTrue();
        assertThat(AiReminderBatchService.isTerminal("PENDING_APPROVAL")).isFalse();
    }

    /**
     * dispatch() replays its stored result for send-attempted statuses. REJECTED must stay out of
     * that set — there is no send outcome to replay, so replaying would answer a forbidden
     * dispatch with a plausible-looking "0 sent, 0 failed" instead of refusing it.
     */
    @Test
    void rejectedIsExcludedFromTheReplayableSendAttemptedSet() {
        assertThat(AiReminderBatchService.SEND_ATTEMPTED_STATUSES)
                .containsExactlyInAnyOrder("SENT", "PARTIALLY_SENT", "FAILED")
                .doesNotContain("REJECTED", "PENDING_APPROVAL");
    }

    /** All three workflows share one lifecycle; a new batch type must not silently opt out. */
    @Test
    void everyReminderBatchTypeSharesTheSameLifecycleContract() {
        Stream.of(new AiFeeReminderBatch(), new AiAttendanceReminderBatch(), new AiTeacherAttendanceReminderBatch())
                .forEach(b -> {
                    assertThat(b).isInstanceOf(AiReminderBatch.class);
                    // Default on construction, before any workflow decision has been made.
                    assertThat(((AiReminderBatch) b).getStatus())
                            .isEqualTo(AiReminderBatchService.PENDING_APPROVAL);
                });
    }
}
