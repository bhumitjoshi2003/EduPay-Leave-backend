package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.AiReminderBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The single authoritative definition of an AI reminder batch's lifecycle, shared by the fee,
 * admin-attendance, and teacher-attendance workflows.
 *
 * <p><b>Why this exists.</b> Approval and rejection were not symmetric. Approval flows through
 * dispatch(), which writes a terminal status to Postgres; rejection was handled entirely inside
 * the LangGraph checkpoint in Redis, so Spring's own row sat at PENDING_APPROVAL forever. The
 * durable audit record therefore could not distinguish "an admin explicitly rejected this batch"
 * from "someone opened the card and wandered off" — and Redis checkpoints expire, so that
 * distinction was eventually lost outright. Rejection is a deliberate human decision not to
 * contact parents; it is exactly the thing an audit trail should retain.
 *
 * <p>This does not move the decision itself out of LangGraph. The graph remains authoritative for
 * workflow state and still drives the response the caller sees; Spring simply records the outcome
 * it was already told about, the same way dispatch() already records a send.
 */
@Service
public class AiReminderBatchService {

    private static final Logger log = LoggerFactory.getLogger(AiReminderBatchService.class);

    public static final String PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String REJECTED = "REJECTED";
    public static final String SENT = "SENT";
    public static final String PARTIALLY_SENT = "PARTIALLY_SENT";
    public static final String FAILED = "FAILED";

    /**
     * Statuses meaning "a send was already attempted for this batch". dispatch() replays its
     * stored result for these rather than sending again. REJECTED is deliberately NOT a member:
     * a rejected batch has no send result to replay, and must be refused outright instead.
     */
    public static final List<String> SEND_ATTEMPTED_STATUSES = List.of(SENT, PARTIALLY_SENT, FAILED);

    /** True once the batch has reached any final state and must never send (again). */
    public static boolean isTerminal(String status) {
        return REJECTED.equals(status) || SEND_ATTEMPTED_STATUSES.contains(status);
    }

    /**
     * Durably records that this batch was rejected.
     *
     * <p>Takes a locking finder rather than an already-loaded entity so the read-check-write runs
     * under the same {@code PESSIMISTIC_WRITE} row lock dispatch() uses — that lock is what makes
     * a concurrent reject and dispatch serialize rather than interleave, so a batch can never be
     * both rejected and sent.
     *
     * <p>Idempotent by the same rule as dispatch(): if the row already holds any terminal status
     * it is left exactly as-is. A retried rejection is a no-op, and a rejection arriving after a
     * send completed never rewrites the send's outcome.
     *
     * @param lockedFinder row-locked lookup, e.g. {@code () -> repo.findByWorkflowIdForUpdate(id)}
     * @return true if this call is what transitioned the row to REJECTED
     */
    @Transactional
    public <T extends AiReminderBatch> boolean markRejected(Supplier<Optional<T>> lockedFinder,
                                                            JpaRepository<T, Long> repository) {
        T batch = lockedFinder.get().orElse(null);
        if (batch == null) {
            // The workflow exists in Redis but has no Spring row — nothing to persist against.
            // Callers have already tenant-checked against that row, so this is close to
            // unreachable; log rather than throw, since the rejection itself did succeed.
            log.warn("Cannot persist rejection: no batch row found for this workflow.");
            return false;
        }
        if (isTerminal(batch.getStatus())) {
            log.debug("Batch {} already terminal ({}) — rejection not re-applied.",
                    batch.getWorkflowId(), batch.getStatus());
            return false;
        }

        batch.setStatus(REJECTED);
        batch.setCompletedAt(LocalDateTime.now());
        repository.save(batch);
        log.info("Batch {} persisted as REJECTED.", batch.getWorkflowId());
        return true;
    }
}
