package com.indraacademy.ias_management.service;

/** eventKind is "IN_PROGRESS" or "RESOLVED" — the only two status changes Phase 1 notifies on
 *  (OPEN is the creation state, never itself an event). revision is included in the listener's
 *  idempotency key so a later status change on the same ticket never collides with an earlier
 *  notification for a prior state. */
public record SupportTicketNotificationEvent(
        Long schoolId, Long ticketId, long revision, String ticketNumber,
        String recipientUserId, String eventKind, String actorUserId) {
}
