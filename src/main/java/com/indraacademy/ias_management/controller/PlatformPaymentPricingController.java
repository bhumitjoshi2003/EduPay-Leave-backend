package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.PaymentPricingConfigDto;
import com.indraacademy.ias_management.dto.PaymentPricingConfigRequest;
import com.indraacademy.ias_management.entity.PaymentPricingConfig;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.PaymentPricingService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Platform-level payment pricing management — SUPER_ADMIN only (the platform-wide role
 * already used for cross-tenant operations like /api/super-admin/schools). Never exposed to
 * school-scoped ADMIN: pricing is a single global commercial schedule, not a per-school
 * setting (see V66's payment_pricing_config, which deliberately carries no school_id). */
@RestController
@RequestMapping("/api/super-admin/payment-pricing")
@PreAuthorize("hasRole('" + Role.SUPER_ADMIN + "')")
public class PlatformPaymentPricingController {

    private static final Logger log = LoggerFactory.getLogger(PlatformPaymentPricingController.class);

    @Autowired private PaymentPricingService paymentPricingService;
    @Autowired private AuthService authService;

    /** Chronological listing (current/scheduled/historical/cancelled) for one provider. */
    @GetMapping
    public ResponseEntity<List<PaymentPricingConfigDto>> list(
            @RequestParam(defaultValue = "RAZORPAY") String gatewayProvider) {
        PaymentPricingService.GatewayProvider provider = parseProvider(gatewayProvider);
        return ResponseEntity.ok(paymentPricingService.list(provider));
    }

    /** Schedules a new immutable pricing version — never edits an existing one. */
    @PostMapping
    public ResponseEntity<PaymentPricingConfigDto> create(@Valid @RequestBody PaymentPricingConfigRequest request) {
        PaymentPricingService.GatewayProvider provider = parseProvider(request.getGatewayProvider());
        String actor = authService.getUserId();
        log.warn("Platform admin {} scheduling new payment pricing: provider={} rateBps={} taxRateBps={} " +
                        "edunexifyFeePaise={} effectiveFrom={}",
                actor, provider, request.getGatewayRateBps(), request.getGatewayTaxRateBps(),
                request.getEdunexifyTransactionFeePaise(), request.getEffectiveFrom());
        PaymentPricingConfig created = paymentPricingService.createVersion(
                provider, request.getGatewayRateBps(), request.getGatewayTaxRateBps(),
                request.getEdunexifyTransactionFeePaise(), request.getEffectiveFrom(), actor);
        // Freshly created — status is derived the same way list() does, but a single-row
        // round trip through list() would be wasteful; a just-created row is always either
        // CURRENT (effectiveFrom <= now) or SCHEDULED (effectiveFrom in the future).
        String status = created.getEffectiveFrom().isAfter(java.time.Instant.now()) ? "SCHEDULED" : "CURRENT";
        return ResponseEntity.ok(PaymentPricingConfigDto.from(created, status));
    }

    /** Cancels a still-scheduled (not yet effective) version — never a current/historical one. */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<PaymentPricingConfigDto> cancel(@PathVariable Long id) {
        String actor = authService.getUserId();
        log.warn("Platform admin {} cancelling scheduled payment pricing version {}", actor, id);
        PaymentPricingConfig cancelled = paymentPricingService.cancelScheduled(id, actor);
        return ResponseEntity.ok(PaymentPricingConfigDto.from(cancelled, "CANCELLED"));
    }

    private PaymentPricingService.GatewayProvider parseProvider(String value) {
        try {
            return PaymentPricingService.GatewayProvider.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown gateway provider: " + value);
        }
    }
}
