package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.FeePayment;
import com.indraacademy.ias_management.entity.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface FeePaymentRepository extends JpaRepository<FeePayment, Long> {

    Optional<FeePayment> findByIdAndSchoolId(Long id, Long schoolId);

    Optional<FeePayment> findByRazorpayOrderIdAndSchoolId(String razorpayOrderId, Long schoolId);

    boolean existsByRazorpayPaymentId(String razorpayPaymentId);

    Page<FeePayment> findBySchoolIdAndStudentId(Long schoolId, String studentId, Pageable pageable);

    Page<FeePayment> findBySchoolIdAndStatus(Long schoolId, PaymentStatus status, Pageable pageable);

    @Query("SELECT p FROM FeePayment p WHERE p.schoolId = :schoolId " +
            "AND (:studentId IS NULL OR p.studentId = :studentId) " +
            "AND (:status IS NULL OR p.status = :status) " +
            "AND (:since IS NULL OR p.paymentDate >= :since) " +
            "ORDER BY p.paymentDate DESC")
    Page<FeePayment> findFiltered(
            @Param("schoolId") Long schoolId,
            @Param("studentId") String studentId,
            @Param("status") PaymentStatus status,
            @Param("since") LocalDateTime since,
            Pageable pageable);

    /** Total collected in a given month/year for dashboard */
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM FeePayment p " +
            "WHERE p.schoolId = :schoolId AND p.status = 'SUCCESS' " +
            "AND MONTH(p.paymentDate) = :month AND YEAR(p.paymentDate) = :year")
    long sumCollectedByMonthAndYear(
            @Param("schoolId") Long schoolId,
            @Param("month") int month,
            @Param("year") int year);

    List<FeePayment> findBySchoolIdAndPaymentDateAfterAndStatus(
            Long schoolId, LocalDateTime since, PaymentStatus status);
}
