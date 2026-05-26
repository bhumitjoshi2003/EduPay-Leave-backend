package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.PaymentAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PaymentAllocationRepository extends JpaRepository<PaymentAllocation, Long> {

    List<PaymentAllocation> findByInvoiceId(Long invoiceId);

    List<PaymentAllocation> findByFeePaymentId(Long feePaymentId);

    @Query("SELECT COALESCE(SUM(pa.amountAllocated), 0) FROM PaymentAllocation pa " +
            "WHERE pa.invoice.id = :invoiceId")
    long sumAllocatedToInvoice(@Param("invoiceId") Long invoiceId);
}
