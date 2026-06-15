package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.service.RazorpayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Handles incoming Razorpay webhooks.
 * This endpoint is excluded from JWT authentication and tenant validation
 * (see SecurityConfig permitAll and filter skip lists).
 * Protected by Razorpay webhook signature verification instead.
 */
@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    @Autowired
    private RazorpayService razorpayService;

    /**
     * Receives Razorpay webhook events (payment.authorized, payment.captured, payment.failed, etc.).
     * Always returns 200 OK to prevent Razorpay from retrying, even if processing fails internally.
     */
    @PostMapping("/razorpay")
    public ResponseEntity<String> handleRazorpayWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {

        log.info("Received Razorpay webhook event.");

        // 1. Verify webhook signature
        if (signature == null || signature.isBlank()) {
            log.warn("Webhook received without X-Razorpay-Signature header. Ignoring.");
            return ResponseEntity.ok("ignored");
        }

        if (!razorpayService.verifyWebhookSignature(payload, signature)) {
            log.warn("Webhook signature verification failed. Possible spoofed request.");
            // Still return 200 to avoid Razorpay retries for invalid signatures
            return ResponseEntity.ok("signature_invalid");
        }

        // 2. Process the webhook event
        try {
            razorpayService.processWebhookEvent(payload);
        } catch (Exception e) {
            log.error("Error processing Razorpay webhook event.", e);
        }

        // 3. Always return 200 OK to Razorpay
        return ResponseEntity.ok("ok");
    }
}
