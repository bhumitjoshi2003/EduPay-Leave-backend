package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.Refund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface RefundRepository extends JpaRepository<Refund, Long> {

    /** Total already refunded against a payment, across all prior (partial) refund events —
     * the basis for over-refund / duplicate-refund rejection. */
    @Query("SELECT COALESCE(SUM(r.amountPaise), 0) FROM Refund r WHERE r.paymentId = :paymentId")
    long sumAmountPaiseByPaymentId(@Param("paymentId") Long paymentId);

    boolean existsByPaymentIdAndIdempotencyKey(Long paymentId, String idempotencyKey);

    /** Phase B (refund reservation): loads the actual row behind a client idempotency key
     * (not just its existence) so a retry can be routed by the row's current status — reuse
     * a still-PENDING reservation, report a terminal SUCCESS/FAILED outcome — rather than
     * always being rejected outright. */
    java.util.Optional<Refund> findByPaymentIdAndIdempotencyKey(Long paymentId, String idempotencyKey);

    /** Phase C (reconciliation/webhook recovery): locates the local Refund row a provider
     * refund id belongs to — the correlation key for both webhook-driven and pull-based
     * reconciliation. provider_refund_id has no uniqueness constraint at the DB level (unlike
     * provider_idempotency_key), but is unique in practice: it is set exactly once, either at
     * finalize time or by {@code RefundSettlementService.recordProviderRefundId}, and never
     * reused across two different logical refunds. */
    java.util.Optional<Refund> findByProviderRefundId(String providerRefundId);

    /** Phase D (operational reconciliation): candidates for the scheduled reconciler — a PENDING
     * refund old enough that it can no longer be an in-flight create/webhook race (the caller
     * supplies that threshold), and with a known provider id (a PENDING row with no provider id
     * at all is the ambiguous-create case, deliberately excluded here — see
     * {@link com.indraacademy.ias_management.service.RazorpayService#reconcileRefund}, which
     * never calls {@code createRefund} again for it either). Bounded via {@code Pageable}
     * (Spring Data applies LIMIT/OFFSET even with a custom {@code @Query}) so a large backlog is
     * never loaded in one pass — oldest first, so a persistently-stuck row surfaces before newer
     * ones on every run rather than starving behind fresh candidates. */
    // Literal 'PENDING' mirrors RefundSettlementService.STATUS_PENDING — not referenced directly
    // to avoid a repository-layer compile dependency on the service layer for one constant.
    @Query("SELECT r FROM Refund r WHERE r.status = 'PENDING' "
            + "AND r.providerRefundId IS NOT NULL AND r.createdAt < :threshold ORDER BY r.createdAt ASC")
    java.util.List<Refund> findStalePendingRefundsWithProviderId(
            @Param("threshold") java.time.LocalDateTime threshold, org.springframework.data.domain.Pageable pageable);

    /** Refunds processed (not the original payment's date) in a given calendar month/year —
     * the basis for net revenue = gross payments − refunds, both computed on their own
     * period, matching how the equivalent Payment sum already works. */
    @Query("SELECT COALESCE(SUM(r.amountPaise), 0) FROM Refund r WHERE r.schoolId = :schoolId "
            + "AND EXTRACT(MONTH FROM r.createdAt) = :month AND EXTRACT(YEAR FROM r.createdAt) = :year")
    long sumAmountPaiseBySchoolIdAndMonthAndYear(@Param("schoolId") Long schoolId, @Param("month") int month, @Param("year") int year);

    List<Refund> findBySchoolIdAndCreatedAtAfter(Long schoolId, LocalDateTime since);

    /** Platform-wide (SUPER_ADMIN dashboard — across all schools) equivalent of
     * sumAmountPaiseBySchoolIdAndMonthAndYear, mirroring PaymentRepository.
     * sumAmountCollectedByMonthAndYear's own school-scoped/platform-wide pair. */
    @Query("SELECT COALESCE(SUM(r.amountPaise), 0) FROM Refund r "
            + "WHERE EXTRACT(MONTH FROM r.createdAt) = :month AND EXTRACT(YEAR FROM r.createdAt) = :year")
    long sumAmountPaiseByMonthAndYear(@Param("month") int month, @Param("year") int year);
}
