package com.indraacademy.ias_management.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request body for updating a school's Razorpay credentials. */
public class RazorpayKeysRequest {

    @NotBlank(message = "Razorpay key ID is required")
    @Size(max = 255, message = "Key ID must not exceed 255 characters")
    private String keyId;

    @NotBlank(message = "Razorpay key secret is required")
    @Size(max = 255, message = "Key secret must not exceed 255 characters")
    private String keySecret;

    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }

    public String getKeySecret() { return keySecret; }
    public void setKeySecret(String keySecret) { this.keySecret = keySecret; }
}
