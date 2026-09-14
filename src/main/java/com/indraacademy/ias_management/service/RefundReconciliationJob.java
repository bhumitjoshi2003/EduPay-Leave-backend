package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Refund;
import com.indraacademy.ias_management.repository.RefundRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Refund-Integrity Hardening, Phase D — the operational mechanism that closes the "PENDING
 * forever" gap: a missed webhook, a failed webhook delivery, a local finalize failure that
 * happened while nothing was watching, or an application restart. Deliberately thin: this class
 * owns only candidate selection and per-candidate isolation; the actual reconciliation decision
 * (finalize / release / no-op) is entirely {@link RazorpayService#reconcileRefund} and
 * {@link RefundSettlementService#resolveFromProviderState} — the exact same state machine the
 * webhook path uses. Never duplicated here.
 * <p>
 * Never calls {@code RazorpayService.createRefund} — only ever inspects and resolves an
 * <em>existing</em> provider refund. A PENDING row with no {@code providerRefundId} at all (the
 * ambiguous-create case) is deliberately excluded from the candidate query
 * ({@link RefundRepository#findStalePendingRefundsWithProviderId}) — there is nothing safe to
 * look up for it, and calling {@code createRefund} again could double-refund if the original
 * request actually reached Razorpay. Those rows are surfaced only via logging (Task 11), never
 * acted on automatically.
 * <p>
 * <b>Multi-instance safety</b> — deliberately has no distributed lock (no ShedLock, no DB-row
 * leasing like {@link NotificationDeliveryWorker}'s claim mechanism). Two reasons: (1) this
 * application's current deployment (see {@code deploy.yml}: a single {@code docker run}, no
 * replica count) is single-instance; (2) even if two instances raced on the exact same refund,
 * {@link RefundSettlementService#resolveFromProviderState} already makes that safe by
 * construction — it locks the Payment row, re-reads the Refund's status <i>inside</i> that lock
 * (never trusting a status read from before the lock), and no-ops on an already-terminal row.
 * Real-Postgres proof: {@code RefundReconciliationJobPostgresIT}. Adding a distributed lock here
 * would be safety infrastructure for a race that is already closed, and the task's own
 * instructions are explicit that this should not be built merely for elegance.
 * <p>
 * The {@link #running} guard exists only to stop a single JVM from overlapping a scheduled pass
 * with itself if one run takes longer than the fixed delay — the same reason
 * {@link NotificationDeliveryWorker#poll()} has one — not a substitute for the above.
 */
@Service
public class RefundReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(RefundReconciliationJob.class);

    private final RefundRepository refundRepository;
    private final RazorpayService razorpayService;
    private final AtomicBoolean running = new AtomicBoolean();

    @Value("${refund.reconciliation.enabled:true}")
    private boolean enabled;

    @Value("${refund.reconciliation.pending-min-age-minutes:5}")
    private long pendingMinAgeMinutes;

    @Value("${refund.reconciliation.batch-size:25}")
    private int batchSize;

    public RefundReconciliationJob(RefundRepository refundRepository, RazorpayService razorpayService) {
        this.refundRepository = refundRepository;
        this.razorpayService = razorpayService;
    }

    /**
     * The minimum age exists specifically so this job never races an in-flight create response
     * or a webhook arriving through the normal, fast path — a refund reserved 30 seconds ago is
     * expected to still be PENDING and is not evidence of anything stuck; the default 5-minute
     * floor is comfortably beyond normal create-to-webhook latency. Age only ever decides WHEN
     * to look, never WHAT the financial outcome is (Task 13) — the outcome is decided entirely
     * by {@code resolveFromProviderState} from the provider's own reported status.
     */
    @Scheduled(fixedDelayString = "${refund.reconciliation.fixed-delay-ms:300000}",
            initialDelayString = "${refund.reconciliation.initial-delay-ms:60000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.debug("Refund reconciliation poll skipped — this instance is still running the previous pass.");
            return;
        }
        try {
            reconcileBatch();
        } catch (Exception e) {
            log.error("Refund reconciliation poll failed before completing its batch.", e);
        } finally {
            running.set(false);
        }
    }

    /** The actual batch logic, kept separate from {@link #poll()} so it can be unit-tested
     * directly without needing Spring's scheduler machinery involved at all. */
    void reconcileBatch() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(pendingMinAgeMinutes);
        List<Refund> candidates = refundRepository.findStalePendingRefundsWithProviderId(
                threshold, PageRequest.of(0, Math.max(1, batchSize)));

        if (candidates.isEmpty()) {
            return;
        }
        log.info("Refund reconciliation: {} stale PENDING refund(s) with a known provider id to inspect.", candidates.size());

        for (Refund candidate : candidates) {
            reconcileOne(candidate);
        }
    }

    /** One candidate never affects another — a bad refund (a provider lookup exception, an
     * unexpected local error) is logged and skipped, not allowed to abort the remaining batch. */
    private void reconcileOne(Refund candidate) {
        // Never logs a full provider payload, a secret, or student-identifying detail — only
        // the identifiers needed to find this refund again (Task 11).
        log.info("Refund reconciliation: inspecting refundId={} paymentId={} providerRefundId={}.",
                candidate.getId(), candidate.getPaymentId(), candidate.getProviderRefundId());
        try {
            razorpayService.reconcileRefund(candidate.getId());
        } catch (Exception e) {
            log.error("Refund reconciliation: unexpected error reconciling refundId={} — left untouched, will be " +
                    "retried on a future pass.", candidate.getId(), e);
        }
    }
}
