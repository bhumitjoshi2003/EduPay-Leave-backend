package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.PaymentPricingConfigDto;
import com.indraacademy.ias_management.entity.PaymentPricingConfig;
import com.indraacademy.ias_management.repository.PaymentPricingConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Platform-level (never school-scoped) authority for CURRENT/FUTURE online payment pricing —
 * replaces the retired environment-variable-backed PaymentPricingProperties. A pricing version
 * is immutable once created; "changing" pricing is always a new version with a later
 * effectiveFrom, never an edit. A PaymentOrder/Payment's own snapshot fields remain the sole
 * historical authority for an already-created transaction — this service is consulted only at
 * checkout-quote/order-creation time, never at settlement (see PaymentSettlementService).
 */
@Service
public class PaymentPricingService {

    private static final Logger log = LoggerFactory.getLogger(PaymentPricingService.class);

    public enum GatewayProvider { RAZORPAY }

    @Autowired private PaymentPricingConfigRepository repository;
    @Autowired private Clock clock;

    /** Thrown when online payment pricing cannot be determined right now — no active
     * configuration exists for the given provider. Callers must fail the quote/order cleanly
     * (never fall back to a hardcoded rate, an environment variable, or zero). */
    public static class PricingUnavailableException extends IllegalStateException {
        public PricingUnavailableException(String message) { super(message); }
    }

    /** The one authoritative source for "what does an online payment cost right now" —
     * PaymentController/StudentFeesController call this immediately before invoking
     * OnlinePaymentPricingCalculator, never reading gatewayRateBps/etc. from any other source. */
    @Transactional(readOnly = true)
    public PaymentPricingConfig resolveActive(GatewayProvider provider) {
        return repository.findActive(provider.name(), clock.instant())
                .orElseThrow(() -> new PricingUnavailableException(
                        "Online payment pricing has not been configured for " + provider + "."));
    }

    /** Chronological listing for the Platform Admin screen — CURRENT/SCHEDULED/HISTORICAL/
     * CANCELLED are all derived here, against the same Clock resolveActive uses, so the
     * frontend never reimplements the effective-time comparison. */
    @Transactional(readOnly = true)
    public List<PaymentPricingConfigDto> list(GatewayProvider provider) {
        Instant now = clock.instant();
        List<PaymentPricingConfig> all = repository.findByGatewayProviderOrderByEffectiveFromDesc(provider.name());
        // The active row is the same one resolveActive would pick: latest non-cancelled with
        // effectiveFrom <= now. `all` is already ordered effectiveFrom DESC, so the first
        // matching row here is exactly that.
        Long currentId = all.stream()
                .filter(c -> c.getCancelledAt() == null && !c.getEffectiveFrom().isAfter(now))
                .map(PaymentPricingConfig::getId)
                .findFirst().orElse(null);
        return all.stream().map(c -> {
            String status;
            if (c.getCancelledAt() != null) {
                status = "CANCELLED";
            } else if (c.getId().equals(currentId)) {
                status = "CURRENT";
            } else if (c.getEffectiveFrom().isAfter(now)) {
                status = "SCHEDULED";
            } else {
                status = "HISTORICAL";
            }
            return PaymentPricingConfigDto.from(c, status);
        }).toList();
    }

    /** Creates a new immutable pricing version. {@code effectiveFrom == null} means "effective
     * immediately" (resolved here, against the server clock — never a client-supplied "now",
     * closing any client/server clock-skew race). An explicitly supplied effectiveFrom in the
     * past is rejected outright; the server clock is always the authority on what "past" means. */
    @Transactional
    public PaymentPricingConfig createVersion(GatewayProvider provider, int gatewayRateBps, int gatewayTaxRateBps,
                                               long edunexifyTransactionFeePaise, Instant effectiveFrom, String createdBy) {
        if (gatewayRateBps < 0 || gatewayRateBps >= 10_000) {
            throw new IllegalArgumentException("gatewayRateBps must be between 0 and 9999.");
        }
        if (gatewayTaxRateBps < 0 || gatewayTaxRateBps >= 10_000) {
            throw new IllegalArgumentException("gatewayTaxRateBps must be between 0 and 9999.");
        }
        if (edunexifyTransactionFeePaise < 0) {
            throw new IllegalArgumentException("edunexifyTransactionFeePaise must not be negative.");
        }

        Instant now = clock.instant();
        Instant resolvedEffectiveFrom = effectiveFrom != null ? effectiveFrom : now;
        if (effectiveFrom != null && effectiveFrom.isBefore(now)) {
            throw new IllegalArgumentException("effectiveFrom cannot be in the past.");
        }

        PaymentPricingConfig config = new PaymentPricingConfig();
        config.setGatewayProvider(provider.name());
        config.setGatewayRateBps(gatewayRateBps);
        config.setGatewayTaxRateBps(gatewayTaxRateBps);
        config.setEdunexifyTransactionFeePaise(edunexifyTransactionFeePaise);
        config.setEffectiveFrom(resolvedEffectiveFrom);
        config.setCreatedAt(now);
        config.setCreatedBy(createdBy);

        try {
            PaymentPricingConfig saved = repository.save(config);
            log.info("Created payment pricing version id={} provider={} rateBps={} taxRateBps={} edunexifyFeePaise={} effectiveFrom={} by={}",
                    saved.getId(), provider, gatewayRateBps, gatewayTaxRateBps, edunexifyTransactionFeePaise, resolvedEffectiveFrom, createdBy);
            return saved;
        } catch (DataIntegrityViolationException e) {
            // uq_ppc_provider_effective_from_active — another active version for this provider
            // already exists at the exact same instant.
            log.warn("Rejected duplicate payment pricing schedule for provider={} effectiveFrom={}", provider, resolvedEffectiveFrom, e);
            throw new IllegalStateException(
                    "A pricing version for " + provider + " is already scheduled at that exact effective time.");
        }
    }

    /** Cancels a still-future, not-yet-effective, not-already-cancelled version. Never deletes
     * the row; never touches a current/historical version — cancellation cannot rewrite the
     * past, only prevent a scheduled future change from ever taking effect. */
    @Transactional
    public PaymentPricingConfig cancelScheduled(Long id, String cancelledBy) {
        PaymentPricingConfig config = repository.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("Pricing version not found: " + id));
        if (config.getCancelledAt() != null) {
            throw new IllegalStateException("This pricing version is already cancelled.");
        }
        Instant now = clock.instant();
        if (!config.getEffectiveFrom().isAfter(now)) {
            throw new IllegalStateException(
                    "Only a still-scheduled (not yet effective) pricing version can be cancelled.");
        }
        config.setCancelledAt(now);
        config.setCancelledBy(cancelledBy);
        PaymentPricingConfig saved = repository.save(config);
        log.info("Cancelled scheduled payment pricing version id={} by={}", id, cancelledBy);
        return saved;
    }
}
