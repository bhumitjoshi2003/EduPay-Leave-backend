package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Refund;
import com.indraacademy.ias_management.repository.RefundRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

/**
 * Replaces {@link RefundReconciliationJob}'s unconditional 5-minute PostgreSQL poll (still
 * present, but gated off by the {@code refund.reconciliation.dynamic.enabled} flag when this is
 * active — see that class) with a bounded, per-refund one-shot follow-up chain: a follow-up is
 * scheduled only for a refund that genuinely needs one (PENDING with a known providerRefundId),
 * checked at a tapering backoff, and stopped the moment the refund reaches a terminal state —
 * whether that terminal state came from this scheduler's own follow-up, a webhook, or the
 * refund's own synchronous creation response.
 *
 * <h2>Design summary</h2>
 * <ul>
 *   <li>A follow-up is scheduled/re-evaluated only via {@link RefundReconciliationScheduleChangedEvent},
 *       published from {@link RefundSettlementService}'s three state-changing methods
 *       (recordProviderRefundId / finalizeSuccessfulRefund / markFailedAndRelease) — never
 *       unconditionally, never on a timer.</li>
 *   <li>Execution reuses {@link RazorpayService#reconcileRefund(Long)} verbatim — the exact same
 *       state machine ({@link RefundSettlementService#resolveFromProviderState}) the webhook path
 *       uses. This class never resolves provider state itself and never calls
 *       {@code RazorpayService.createRefund} — only the existing read-only
 *       {@code fetchRefund}/{@code reconcileRefund} path.</li>
 *   <li>Bounded backoff ({@link #BACKOFF_DELAYS}): 5m, 15m, 1h, 6h, 24h — 5 attempts, ~31h20m
 *       total active window. After the last attempt, if still PENDING, active follow-up stops;
 *       the daily {@link #safetySweep()} remains the permanent backstop.</li>
 *   <li>Multi-instance safety relies entirely on the existing, already-proven
 *       {@code RefundSettlementService.resolveFromProviderState} lock+refresh pattern — see that
 *       method's javadoc and {@link RefundReconciliationJob}'s own javadoc for why no new
 *       distributed lock is added here: two concurrent calls for the same refund already
 *       serialize safely on the Payment row lock, and a duplicate read-only provider GET has no
 *       side effects to duplicate.</li>
 * </ul>
 */
@Service
public class RefundReconciliationDynamicScheduler {

    private static final Logger log = LoggerFactory.getLogger(RefundReconciliationDynamicScheduler.class);

    /** Tapering follow-up delays: near-term recovery for a missed webhook, then increasingly
     * rare checks so a genuinely slow-to-resolve refund doesn't generate needless traffic. */
    static final Duration[] BACKOFF_DELAYS = {
            Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofHours(1),
            Duration.ofHours(6), Duration.ofHours(24)
    };

    @Value("${refund.reconciliation.dynamic.enabled:false}")
    private boolean dynamicEnabled;

    @Value("${refund.reconciliation.pending-min-age-minutes:5}")
    private long pendingMinAgeMinutes;

    @Value("${refund.reconciliation.batch-size:25}")
    private int batchSize;

    @Autowired private RefundRepository refundRepository;
    @Autowired private RazorpayService razorpayService;
    @Autowired private RefundReconciliationScheduleRegistry registry;
    @Autowired
    @Qualifier("refundReconciliationTaskScheduler")
    private TaskScheduler taskScheduler;
    @Autowired private Clock clock;

    // ─── Startup rebuild ────────────────────────────────────────────────────────

    @EventListener(ApplicationReadyEvent.class)
    public void rebuildSchedulesOnStartup() {
        if (!dynamicEnabled) {
            return;
        }
        List<Refund> candidates = staleCandidates();
        for (Refund refund : candidates) {
            scheduleAttempt(refund.getId(), 0);
        }
        log.info("Refund reconciliation dynamic scheduling: rebuilt {} follow-up schedule(s) on startup.",
                candidates.size());
    }

    // ─── Rare safety sweep (daily by default) — the restart-proof, always-on backstop ────────

    @Scheduled(fixedDelayString = "${refund.reconciliation.safety-interval-ms:86400000}")
    public void safetySweep() {
        if (!dynamicEnabled) {
            return;
        }
        List<Refund> candidates = staleCandidates();
        int newlyScheduled = 0;
        for (Refund refund : candidates) {
            if (!registry.isScheduled(refund.getId())) {
                scheduleAttempt(refund.getId(), 0);
                newlyScheduled++;
            }
        }
        if (newlyScheduled > 0) {
            log.info("Refund reconciliation safety sweep: {} stale PENDING refund(s) had no active " +
                    "follow-up — rescheduled.", newlyScheduled);
        }
    }

    private List<Refund> staleCandidates() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(pendingMinAgeMinutes);
        return refundRepository.findStalePendingRefundsWithProviderId(threshold, PageRequest.of(0, Math.max(1, batchSize)));
    }

    // ─── Refund state change — reschedule only after commit ──────────────────────

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onScheduleChanged(RefundReconciliationScheduleChangedEvent event) {
        if (!dynamicEnabled) {
            return;
        }
        reevaluate(event.refundId());
    }

    /**
     * Cancels any currently-scheduled follow-up for this refund, then re-evaluates its CURRENT,
     * authoritative state from PostgreSQL: terminal → stay cancelled; PENDING with a
     * providerRefundId → schedule attempt 0 (a fresh backoff sequence — the attempt counter is
     * in-memory bookkeeping only, restarting it on every state change is safe and simple);
     * anything else (missing providerRefundId, refund not found) → stay cancelled.
     */
    private void reevaluate(Long refundId) {
        registry.cancel(refundId);
        Optional<Refund> refundOpt = refundRepository.findById(refundId);
        if (refundOpt.isEmpty()) {
            return;
        }
        Refund refund = refundOpt.get();
        if (needsFollowUp(refund)) {
            scheduleAttempt(refundId, 0);
        }
    }

    private boolean needsFollowUp(Refund refund) {
        return RefundSettlementService.STATUS_PENDING.equals(refund.getStatus())
                && refund.getProviderRefundId() != null && !refund.getProviderRefundId().isBlank();
    }

    private boolean isTerminal(Refund refund) {
        return RefundSettlementService.STATUS_SUCCESS.equals(refund.getStatus())
                || RefundSettlementService.STATUS_FAILED.equals(refund.getStatus());
    }

    private void scheduleAttempt(Long refundId, int attemptIndex) {
        Instant fireAt = Instant.now(clock).plus(BACKOFF_DELAYS[attemptIndex]);
        ScheduledFuture<?> future = taskScheduler.schedule(() -> executeFollowUp(refundId, attemptIndex), fireAt);
        registry.put(refundId, future);
    }

    // ─── Execution ──────────────────────────────────────────────────────────────

    private void executeFollowUp(Long refundId, int attemptIndex) {
        Optional<Refund> refundOpt;
        try {
            refundOpt = refundRepository.findById(refundId);
        } catch (Exception dbFailure) {
            log.error("Refund reconciliation follow-up failed to reload refundId={}: {}",
                    refundId, dbFailure.getMessage(), dbFailure);
            scheduleNextOrStop(refundId, attemptIndex);
            return;
        }

        if (refundOpt.isEmpty()) {
            log.info("Refund reconciliation: refund {} no longer exists — removing its follow-up schedule.", refundId);
            registry.remove(refundId);
            return;
        }

        Refund refund = refundOpt.get();
        if (isTerminal(refund)) {
            log.info("Refund reconciliation: refund {} is already '{}' — removing its follow-up schedule.",
                    refundId, refund.getStatus());
            registry.remove(refundId);
            return;
        }
        if (refund.getProviderRefundId() == null || refund.getProviderRefundId().isBlank()) {
            log.warn("Refund reconciliation: refund {} is PENDING with no providerRefundId — cannot safely " +
                    "reconcile; not scheduling a further follow-up. Requires manual verification.", refundId);
            registry.remove(refundId);
            return;
        }

        try {
            razorpayService.reconcileRefund(refundId);
        } catch (Exception e) {
            // Mirrors RazorpayService.reconcileRefund's own internal handling — this catch exists
            // only so a defensive failure here never crashes the scheduled task itself.
            log.error("Refund reconciliation follow-up: unexpected error reconciling refundId={} — will retry " +
                    "at the next bounded backoff step.", refundId, e);
        }

        Refund reloaded = refundRepository.findById(refundId).orElse(null);
        if (reloaded == null || isTerminal(reloaded)) {
            log.info("Refund reconciliation: refund {} resolved to a terminal state — follow-up complete.", refundId);
            registry.remove(refundId);
            return;
        }

        scheduleNextOrStop(refundId, attemptIndex);
    }

    /** Still PENDING (or a transient DB/provider failure just occurred) — advance to the next
     * bounded backoff step if budget remains, otherwise stop the active chain and rely on the
     * daily safety sweep from here on. Never a tight loop, never unbounded. */
    private void scheduleNextOrStop(Long refundId, int attemptIndex) {
        int nextAttemptIndex = attemptIndex + 1;
        if (nextAttemptIndex < BACKOFF_DELAYS.length) {
            scheduleAttempt(refundId, nextAttemptIndex);
        } else {
            log.info("Refund reconciliation: refund {} exhausted {} active follow-up attempts and remains " +
                    "PENDING — stopping active follow-up; the daily safety sweep will continue checking it.",
                    refundId, BACKOFF_DELAYS.length);
            registry.remove(refundId);
        }
    }
}
