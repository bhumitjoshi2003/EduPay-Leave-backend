package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.Payment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    @Query("SELECT p FROM Payment p WHERE p.schoolId = :schoolId AND p.className = :className AND p.studentId LIKE %:studentId% AND DATE(p.paymentDate) = :paymentDate")
    Page<Payment> findBySchoolIdAndClassNameAndStudentIdContainingAndPaymentDate(
            @Param("schoolId") Long schoolId,
            @Param("className") String className,
            @Param("studentId") String studentId,
            @Param("paymentDate") LocalDate paymentDate,
            Pageable pageable);

    Page<Payment> findBySchoolIdAndClassNameAndStudentIdContaining(Long schoolId, String className, String studentId, Pageable pageable);

    @Query("SELECT p FROM Payment p WHERE p.schoolId = :schoolId AND p.className = :className AND DATE(p.paymentDate) = :paymentDate")
    Page<Payment> findBySchoolIdAndClassNameAndPaymentDate(
            @Param("schoolId") Long schoolId,
            @Param("className") String className,
            @Param("paymentDate") LocalDate paymentDate,
            Pageable pageable);

    @Query("SELECT p FROM Payment p WHERE p.schoolId = :schoolId AND p.studentId LIKE %:studentId% AND DATE(p.paymentDate) = :paymentDate")
    Page<Payment> findBySchoolIdAndStudentIdContainingAndPaymentDate(
            @Param("schoolId") Long schoolId,
            @Param("studentId") String studentId,
            @Param("paymentDate") LocalDate paymentDate,
            Pageable pageable);

    Page<Payment> findBySchoolIdAndClassName(Long schoolId, String className, Pageable pageable);

    Page<Payment> findBySchoolIdAndStudentIdContaining(Long schoolId, String studentId, Pageable pageable);

    @Query("SELECT p FROM Payment p WHERE p.schoolId = :schoolId AND DATE(p.paymentDate) = :paymentDate")
    Page<Payment> findBySchoolIdAndPaymentDate(@Param("schoolId") Long schoolId, @Param("paymentDate") LocalDate paymentDate, Pageable pageable);

    Page<Payment> findBySchoolIdAndStudentId(Long schoolId, String studentId, Pageable pageable);

    Payment findByPaymentId(String paymentId);

    @Query("SELECT MAX(p.paymentDate) FROM Payment p WHERE p.studentId = :studentId AND p.schoolId = :schoolId AND p.session = :session")
    java.util.Optional<java.time.LocalDateTime> findLatestPaymentDateByStudentIdAndSchoolIdAndSession(
            @Param("studentId") String studentId,
            @Param("schoolId") Long schoolId,
            @Param("session") String session);

    @Query("SELECT COALESCE(SUM(p.amountPaid - p.platformFee), 0) FROM Payment p WHERE p.schoolId = :schoolId AND EXTRACT(MONTH FROM p.paymentDate) = :month AND EXTRACT(YEAR FROM p.paymentDate) = :year")
    long sumAmountCollectedBySchoolIdAndMonthAndYear(@Param("schoolId") Long schoolId, @Param("month") int month, @Param("year") int year);

    List<Payment> findBySchoolIdAndPaymentDateAfter(Long schoolId, LocalDateTime since);
}
