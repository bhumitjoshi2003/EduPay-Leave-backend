package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.Payment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    @Query("SELECT p FROM Payment p WHERE p.className = :className AND p.studentId LIKE %:studentId% AND DATE(p.paymentDate) = :paymentDate")
    Page<Payment> findByClassNameAndStudentIdContainingAndPaymentDate(
            @Param("className") String className,
            @Param("studentId") String studentId,
            @Param("paymentDate") LocalDate paymentDate,
            Pageable pageable);
    
    Page<Payment> findByClassNameAndStudentIdContaining(String className, String studentId, Pageable pageable);

    @Query("SELECT p FROM Payment p WHERE p.className = :className AND DATE(p.paymentDate) = :paymentDate")
    Page<Payment> findByClassNameAndPaymentDate(
            @Param("className") String className,
            @Param("paymentDate") LocalDate paymentDate,
            Pageable pageable);

    @Query("SELECT p FROM Payment p WHERE p.studentId LIKE %:studentId% AND DATE(p.paymentDate) = :paymentDate")
    Page<Payment> findByStudentIdContainingAndPaymentDate(
            @Param("studentId") String studentId,
            @Param("paymentDate") LocalDate paymentDate,
            Pageable pageable);

    Page<Payment> findByClassName(String className, Pageable pageable);

    Page<Payment> findByStudentIdContaining(String studentId, Pageable pageable);

    @Query("SELECT p FROM Payment p WHERE DATE(p.paymentDate) = :paymentDate")
    Page<Payment> findByPaymentDate(@Param("paymentDate") LocalDate paymentDate, Pageable pageable);

    Page<Payment> findByStudentId(String studentId, Pageable pageable);

    Payment findByPaymentId(String paymentId);
}