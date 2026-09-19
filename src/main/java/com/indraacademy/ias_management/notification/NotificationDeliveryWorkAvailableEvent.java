package com.indraacademy.ias_management.notification;

/**
 * "There may be notification delivery work" — published from within
 * {@code NotificationPublicationTransaction.publishNew} whenever at least one PENDING
 * {@link com.indraacademy.ias_management.entity.NotificationDelivery} row was just created.
 * Carries no delivery payload on purpose: the authoritative claim query in PostgreSQL
 * (see NotificationDeliveryRepository#lockEligibleIds) re-derives all currently-due work from
 * scratch every time it runs, regardless of which event (or how many) woke it — so the event
 * itself only ever needs to mean "go check now", never "here is exactly what to do".
 *
 * <p>Consumed only via a {@code @TransactionalEventListener(phase = AFTER_COMMIT)} — this event
 * must never be acted upon before the publishing transaction (which may be an enclosing business
 * transaction several calls up the stack, not necessarily {@code publishNew}'s own) has actually
 * committed. See NotificationDeliveryRedisSignaler.
 */
public record NotificationDeliveryWorkAvailableEvent(Long schoolId) {
}
