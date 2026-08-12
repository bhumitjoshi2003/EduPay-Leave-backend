package com.indraacademy.ias_management.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class RefundRequest {

    @NotNull
    @Min(1)
    private Long amount; // in paise

    @NotBlank
    private String reason;

    /** Optional client-generated token — when supplied, a second refund request against the
     * same payment with the same key is rejected as a duplicate rather than processed again
     * (e.g. a UI double-click or a network retry). Omit for a genuinely new refund. */
    private String idempotencyKey;

    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}
