package com.indraacademy.ias_management.service;

/**
 * "This refund's state may need its follow-up schedule re-evaluated" — published from within
 * {@link RefundSettlementService}'s three state-changing methods:
 * {@code recordProviderRefundId} (a provider refund id just became known — a follow-up may now
 * be needed), {@code finalizeSuccessfulRefund} and {@code markFailedAndRelease} (the refund just
 * became terminal — any existing follow-up must stop). One event, one meaning: "reload this
 * refund's current, authoritative state and decide what the runtime schedule should be" — the
 * listener never trusts anything about "what changed" from this event's payload beyond the id.
 *
 * <p>Consumed only via a {@code @TransactionalEventListener(phase = AFTER_COMMIT)} in
 * {@link RefundReconciliationDynamicScheduler} — publishing from within an already-transactional
 * method (all three call sites are {@code @Transactional}) means the listener only ever runs
 * after that state change is durably committed, never before, and never for a call that ends up
 * rolling back.
 */
public record RefundReconciliationScheduleChangedEvent(Long refundId) {
}
