package com.indraacademy.ias_management.dto;

/** Request body for updating a school's Razorpay credentials. */
public class RazorpayKeysRequest {

    private String keyId;
    private String keySecret;

    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }

    public String getKeySecret() { return keySecret; }
    public void setKeySecret(String keySecret) { this.keySecret = keySecret; }
}
