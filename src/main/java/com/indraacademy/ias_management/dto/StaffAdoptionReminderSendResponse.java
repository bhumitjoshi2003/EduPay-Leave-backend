package com.indraacademy.ias_management.dto;

/** {@code skippedRecentCount} reflects teachers who were eligible but already received this
 *  same reminder type today (existing idempotency-key dedup) — never a claim that email/push
 *  was actually delivered, only that the notification was accepted for delivery. */
public record StaffAdoptionReminderSendResponse(
        StaffAdoptionReminderType type,
        int eligibleCount,
        int sentCount,
        int skippedRecentCount) {
}
