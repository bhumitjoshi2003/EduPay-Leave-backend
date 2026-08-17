package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Leave;
import com.indraacademy.ias_management.entity.LeaveStatus;
import com.indraacademy.ias_management.exception.InvalidLeaveStatusTransitionException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Covers the safety layer that stands between an AI-proposed leave decision and the database.
 *
 * <p>This workflow gives up the property every reminder workflow relies on — that the model
 * cannot name targets — because deciding leave is inherently about specific requests. These tests
 * pin the compensating controls: only still-PENDING requests inside the batch's scope are ever
 * changed, and everything else is reported rather than silently acted on or silently dropped.
 */
@ExtendWith(MockitoExtension.class)
class LeaveDecisionServiceTest {

    @Mock private LeaveService leaveService;
    @Mock private HttpServletRequest request;

    @InjectMocks private LeaveDecisionService service;

    private Leave leave(long id, LeaveStatus status, String className) {
        Leave l = new Leave();
        l.setId(id);
        l.setStatus(status);
        l.setClassName(className);
        l.setStudentId("S" + id);
        return l;
    }

    @Test
    void appliesTheDecisionToAStillPendingRequest() {
        when(leaveService.getLeaveById(1L)).thenReturn(Optional.of(leave(1L, LeaveStatus.PENDING, "10")));

        Map<Long, String> outcomes = service.applyDecisions(List.of(1L), LeaveStatus.APPROVED, null, request);

        assertThat(outcomes).containsEntry(1L, LeaveDecisionService.APPLIED);
        // Goes through the existing mutation, which is what audits and notifies the student.
        verify(leaveService).updateLeaveStatus(1L, LeaveStatus.APPROVED, request);
    }

    /**
     * The core race: the batch was drafted while the request was PENDING, another admin decided it
     * in the meantime. updateLeaveStatus itself permits any transition, so without this guard the
     * approval would silently reverse their decision AND re-notify the student.
     */
    @Test
    void neverOverwritesARequestSomeoneElseAlreadyDecided() {
        when(leaveService.getLeaveById(2L)).thenReturn(Optional.of(leave(2L, LeaveStatus.APPROVED, "10")));

        Map<Long, String> outcomes = service.applyDecisions(List.of(2L), LeaveStatus.REJECTED, null, request);

        assertThat(outcomes).containsEntry(2L, LeaveDecisionService.SKIPPED_NOT_PENDING);
        verify(leaveService, never()).updateLeaveStatus(anyLong(), any(), any());
    }

    @Test
    void skipsARequestOutsideATeacherBatchesClass_evenIfItIsPending() {
        when(leaveService.getLeaveById(3L)).thenReturn(Optional.of(leave(3L, LeaveStatus.PENDING, "9")));

        // Batch is confined to class 10; the request belongs to class 9.
        Map<Long, String> outcomes = service.applyDecisions(List.of(3L), LeaveStatus.APPROVED, "10", request);

        assertThat(outcomes).containsEntry(3L, LeaveDecisionService.SKIPPED_WRONG_CLASS);
        verify(leaveService, never()).updateLeaveStatus(anyLong(), any(), any());
    }

    /** An id that doesn't resolve — nonexistent, another school, or deleted since drafting. */
    @Test
    void skipsAnIdThatDoesNotResolve_ratherThanFailingTheWholeBatch() {
        when(leaveService.getLeaveById(99L)).thenReturn(Optional.empty());

        Map<Long, String> outcomes = service.applyDecisions(List.of(99L), LeaveStatus.APPROVED, null, request);

        assertThat(outcomes).containsEntry(99L, LeaveDecisionService.SKIPPED_NOT_FOUND);
        verify(leaveService, never()).updateLeaveStatus(anyLong(), any(), any());
    }

    @Test
    void oneFailureDoesNotAbortTheRest_andIsReportedPerRequest() {
        when(leaveService.getLeaveById(1L)).thenReturn(Optional.of(leave(1L, LeaveStatus.PENDING, "10")));
        when(leaveService.getLeaveById(2L)).thenReturn(Optional.of(leave(2L, LeaveStatus.PENDING, "10")));
        when(leaveService.getLeaveById(3L)).thenReturn(Optional.of(leave(3L, LeaveStatus.PENDING, "10")));
        // lenient: strict stubs would otherwise raise PotentialStubbingProblem for the ids that
        // legitimately don't match this stubbing, and applyDecisions' catch-all would report those
        // as "failed" — hiding the very behaviour under test.
        lenient().doThrow(new RuntimeException("db down"))
                .when(leaveService).updateLeaveStatus(eq(2L), any(), any());

        Map<Long, String> outcomes = service.applyDecisions(List.of(1L, 2L, 3L), LeaveStatus.APPROVED, null, request);

        assertThat(outcomes.values()).containsExactly(
                LeaveDecisionService.APPLIED,
                LeaveDecisionService.FAILED,
                LeaveDecisionService.APPLIED);
        // The request after the failing one was still processed — the loop does not abort.
        verify(leaveService).updateLeaveStatus(3L, LeaveStatus.APPROVED, request);
    }

    @Test
    void aMixedBatchReportsEachRequestIndependently() {
        when(leaveService.getLeaveById(1L)).thenReturn(Optional.of(leave(1L, LeaveStatus.PENDING, "10")));
        when(leaveService.getLeaveById(2L)).thenReturn(Optional.of(leave(2L, LeaveStatus.REJECTED, "10")));
        when(leaveService.getLeaveById(3L)).thenReturn(Optional.empty());

        Map<Long, String> outcomes = service.applyDecisions(List.of(1L, 2L, 3L), LeaveStatus.APPROVED, "10", request);

        assertThat(outcomes.values()).containsExactly(
                LeaveDecisionService.APPLIED,
                LeaveDecisionService.SKIPPED_NOT_PENDING,
                LeaveDecisionService.SKIPPED_NOT_FOUND);
        verify(leaveService, times(1)).updateLeaveStatus(anyLong(), any(), any());
    }

    /** Skips are a correct refusal, not an error — the dispatch status logic depends on this split. */
    @Test
    void skipOutcomesAreClassifiedAsSkipsAndFailureIsNot() {
        assertThat(LeaveDecisionService.isSkip(LeaveDecisionService.SKIPPED_NOT_PENDING)).isTrue();
        assertThat(LeaveDecisionService.isSkip(LeaveDecisionService.SKIPPED_NOT_FOUND)).isTrue();
        assertThat(LeaveDecisionService.isSkip(LeaveDecisionService.SKIPPED_WRONG_CLASS)).isTrue();
        assertThat(LeaveDecisionService.isSkip(LeaveDecisionService.FAILED)).isFalse();
        assertThat(LeaveDecisionService.isSkip(LeaveDecisionService.APPLIED)).isFalse();
    }

    /**
     * The window this closes: applyDecisions checked "still PENDING" and found it true, but
     * between that check and the locked update inside updateLeaveStatus, another caller (or
     * another leave decision batch) got there first — updateLeaveStatus's own same-status guard
     * catches this at the lock and throws, rather than silently re-applying or blindly failing.
     * Reported the same way as the earlier, more common check: a skip, not a failure.
     */
    @Test
    void aRaceLostAtTheLock_isReportedAsSkippedNotPending_notAsAFailure() {
        when(leaveService.getLeaveById(1L)).thenReturn(Optional.of(leave(1L, LeaveStatus.PENDING, "10")));
        doThrow(new InvalidLeaveStatusTransitionException(1L, LeaveStatus.APPROVED, LeaveStatus.APPROVED))
                .when(leaveService).updateLeaveStatus(1L, LeaveStatus.APPROVED, request);

        Map<Long, String> outcomes = service.applyDecisions(List.of(1L), LeaveStatus.APPROVED, null, request);

        assertThat(outcomes).containsEntry(1L, LeaveDecisionService.SKIPPED_NOT_PENDING);
        assertThat(LeaveDecisionService.isSkip(outcomes.get(1L))).isTrue();
    }

    @Test
    void anAdminBatchWithNoClassConfinementActsAcrossClasses() {
        when(leaveService.getLeaveById(4L)).thenReturn(Optional.of(leave(4L, LeaveStatus.PENDING, "9")));

        Map<Long, String> outcomes = service.applyDecisions(List.of(4L), LeaveStatus.APPROVED, null, request);

        assertThat(outcomes).containsEntry(4L, LeaveDecisionService.APPLIED);
    }
}
