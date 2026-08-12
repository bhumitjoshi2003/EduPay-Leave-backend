package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.AllocationRefund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AllocationRefundRepository extends JpaRepository<AllocationRefund, Long> {

    @Query("SELECT COALESCE(SUM(r.amountPaise), 0) FROM AllocationRefund r WHERE r.allocationId = :allocationId")
    long sumAmountPaiseByAllocationId(@Param("allocationId") Long allocationId);

    /** Total reversed against ANY allocation belonging to this StudentFees row — the other
     * half (with PaymentAllocationRepository.sumAmountPaiseByStudentFeesId) of computing the
     * row's true net-paid amount. */
    @Query("SELECT COALESCE(SUM(r.amountPaise), 0) FROM AllocationRefund r WHERE r.studentFeesId = :studentFeesId")
    long sumAmountPaiseByStudentFeesId(@Param("studentFeesId") Long studentFeesId);

    /** Total reversed specifically against manual-payment-funded allocations for this row —
     * pairs with PaymentStudentFeesAllocationRepository.sumManualAmountPaiseByStudentFeesId
     * to derive the row's true net manually-paid amount. */
    @Query("SELECT COALESCE(SUM(r.amountPaise), 0) FROM AllocationRefund r "
            + "JOIN PaymentStudentFeesAllocation a ON a.id = r.allocationId "
            + "JOIN Payment p ON p.id = a.paymentId "
            + "WHERE r.studentFeesId = :studentFeesId AND p.manualPaymentMode IS NOT NULL")
    long sumManualReversedAmountPaiseByStudentFeesId(@Param("studentFeesId") Long studentFeesId);
}
