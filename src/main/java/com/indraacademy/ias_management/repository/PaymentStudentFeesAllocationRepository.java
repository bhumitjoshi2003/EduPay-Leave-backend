package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.PaymentStudentFeesAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Repository for the legacy-fee-module allocation ledger (StudentFees.month bitmask model)
 * — NOT related to the separate Invoice/FeePayment system's own PaymentAllocation entity and
 * PaymentAllocationRepository, which track a different, unrelated invoice-based ledger. */
@Repository
public interface PaymentStudentFeesAllocationRepository extends JpaRepository<PaymentStudentFeesAllocation, Long> {

    List<PaymentStudentFeesAllocation> findByPaymentIdOrderByMonthAsc(Long paymentId);

    List<PaymentStudentFeesAllocation> findByStudentFeesId(Long studentFeesId);

    /** Gross amount ever allocated to this StudentFees row, across all payments — before
     * subtracting any refunds against those allocations. Combine with
     * AllocationRefundRepository.sumAmountPaiseByStudentFeesId to get the row's true net. */
    @Query("SELECT COALESCE(SUM(a.amountPaise), 0) FROM PaymentStudentFeesAllocation a WHERE a.studentFeesId = :studentFeesId")
    long sumAmountPaiseByStudentFeesId(@Param("studentFeesId") Long studentFeesId);

    /** Gross amount allocated to this row from payments whose manualPaymentMode is set (i.e.
     * cash/cheque/UPI/bank-transfer, not a Razorpay-gateway payment) — the basis for deriving
     * StudentFees.manuallyPaid/manualPaymentReceived honestly from the ledger instead of a
     * separately-tracked "last write wins" flag. Ad-hoc join on the plain paymentId FK since
     * PaymentStudentFeesAllocation has no mapped @ManyToOne to Payment. */
    @Query("SELECT COALESCE(SUM(a.amountPaise), 0) FROM PaymentStudentFeesAllocation a "
            + "JOIN Payment p ON p.id = a.paymentId "
            + "WHERE a.studentFeesId = :studentFeesId AND p.manualPaymentMode IS NOT NULL")
    long sumManualAmountPaiseByStudentFeesId(@Param("studentFeesId") Long studentFeesId);

    boolean existsByPaymentId(Long paymentId);
}
