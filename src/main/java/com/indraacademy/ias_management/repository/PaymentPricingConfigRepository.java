package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.PaymentPricingConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentPricingConfigRepository extends JpaRepository<PaymentPricingConfig, Long> {

    /** The single effective configuration for a provider at instant {@code at}: the latest
     * non-cancelled version whose effective_from has already arrived. Deterministic ordering
     * (effectiveFrom desc, id desc as a tiebreaker for the theoretically-impossible case of two
     * rows at the exact same instant — the partial unique index already prevents that for
     * non-cancelled rows, this is defense in depth only). */
    @Query("SELECT c FROM PaymentPricingConfig c WHERE c.gatewayProvider = :provider " +
            "AND c.cancelledAt IS NULL AND c.effectiveFrom <= :at " +
            "ORDER BY c.effectiveFrom DESC, c.id DESC")
    List<PaymentPricingConfig> findActiveCandidates(@Param("provider") String provider, @Param("at") Instant at);

    default Optional<PaymentPricingConfig> findActive(String provider, Instant at) {
        List<PaymentPricingConfig> candidates = findActiveCandidates(provider, at);
        return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.get(0));
    }

    List<PaymentPricingConfig> findByGatewayProviderOrderByEffectiveFromDesc(String gatewayProvider);
}
