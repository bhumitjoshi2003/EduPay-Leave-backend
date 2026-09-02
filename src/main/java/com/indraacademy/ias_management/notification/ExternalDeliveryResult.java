package com.indraacademy.ias_management.notification;

public record ExternalDeliveryResult(
        ExternalDeliveryOutcome outcome,
        String providerMessageId,
        String detail) {

    public static ExternalDeliveryResult sent(String providerMessageId) {
        return new ExternalDeliveryResult(ExternalDeliveryOutcome.SENT, providerMessageId, null);
    }

    public static ExternalDeliveryResult retryable(String detail) {
        return new ExternalDeliveryResult(ExternalDeliveryOutcome.RETRYABLE_FAILURE, null, detail);
    }

    public static ExternalDeliveryResult permanent(String detail) {
        return new ExternalDeliveryResult(ExternalDeliveryOutcome.PERMANENT_FAILURE, null, detail);
    }

    public static ExternalDeliveryResult skipped(String detail) {
        return new ExternalDeliveryResult(ExternalDeliveryOutcome.SKIPPED, null, detail);
    }
}
