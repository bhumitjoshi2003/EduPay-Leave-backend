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
