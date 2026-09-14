package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.service.RazorpayService;
import com.indraacademy.ias_management.observability.UnexpectedErrorReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Handles incoming Razorpay webhooks.
 * This endpoint is excluded from JWT authentication and tenant validation
 * (see SecurityConfig permitAll and filter skip lists).
 * Protected by Razorpay webhook signature verification instead.
 * <p>
 * Signature-related rejections always return 200 (deliberately — never let the response leak
 * whether a signature was valid, which would give an attacker a signature-forging oracle).
 * Beyond that, only a genuinely transient processing failure returns a non-2xx so Razorpay's
 * own retry schedule gets a chance to help; a permanently-inapplicable event (malformed
 * payload, unknown order, amount mismatch, already-consumed-by-a-different-payment) is
 * acknowledged with 200 instead, since retrying an identical payload can never change any of
 * those — see RazorpayService.processWebhookEvent's WebhookProcessingResult.
 */
@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    @Autowired
    private RazorpayService razorpayService;

    @Autowired
    private UnexpectedErrorReporter errorReporter;

    /**
     * Receives Razorpay webhook events (payment.authorized, payment.captured, payment.failed,
     * etc.). See the class javadoc for the response/retry semantics — no longer an
     * unconditional 200 for every outcome.
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

        // 2. Process the webhook event. Only a genuinely transient failure gets a non-2xx
        //    response — Razorpay retries those on its own schedule. Everything else (handled,
        //    already-settled, or permanently inapplicable: malformed payload, unknown order,
        //    amount mismatch, an order already consumed by a different payment) is acknowledged
        //    with 200, since retrying an identical payload can never change any of those.
        try {
            RazorpayService.WebhookProcessingResult result = razorpayService.processWebhookEvent(payload);
            return switch (result.retryDisposition()) {
                case ACKNOWLEDGE -> ResponseEntity.ok(result.detail());
                case RETRY -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result.detail());
            };
        } catch (Exception e) {
            log.error("Error processing Razorpay webhook event.", e);
            errorReporter.report("razorpay.webhook", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("internal_error");
        }
    }
}
