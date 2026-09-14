package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.PaymentOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, Long> {
    Optional<PaymentOrder> findByOrderId(String orderId);
    Optional<PaymentOrder> findByOrderIdAndSchoolId(String orderId, Long schoolId);

    /** Row-level write lock for the duration of the caller's transaction — the primitive
     * future settlement code will use to serialize concurrent verify/webhook attempts against
     * the same PaymentOrder (Razorpay Payment-Integrity Hardening, Phase A; see the audit's
     * Issue A / PaymentOrder.consumed race finding). Not yet used anywhere —
     * RazorpayService.verifyPayment still uses the unlocked findByOrderId above; wiring this
     * in is Phase B's job, not this one's. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT po FROM PaymentOrder po WHERE po.orderId = :orderId")
    Optional<PaymentOrder> findByOrderIdForUpdate(@Param("orderId") String orderId);
}
