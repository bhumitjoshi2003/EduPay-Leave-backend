package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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

    java.util.Optional<Payment> findByPaymentIdAndSchoolId(String paymentId, Long schoolId);

    /** Row-level write lock for the duration of the caller's transaction — used by refund
     * processing so two concurrent refund attempts against the same payment serialize rather
     * than both reading the same "already refunded so far" total and over-refunding. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") Long id);

    boolean existsByPaymentId(String paymentId);

    /** Idempotency guard for manual payments: a given admin-supplied reference (cheque
     * number, UTR, transaction ref) must not be recorded twice within the same school. */
    boolean existsByManualReferenceNumberAndSchoolId(String manualReferenceNumber, Long schoolId);

    @org.springframework.transaction.annotation.Transactional
    void deleteByStudentIdAndSchoolId(String studentId, Long schoolId);

    @Query("SELECT MAX(p.paymentDate) FROM Payment p WHERE p.studentId = :studentId AND p.schoolId = :schoolId AND p.session = :session")
    java.util.Optional<java.time.LocalDateTime> findLatestPaymentDateByStudentIdAndSchoolIdAndSession(
            @Param("studentId") String studentId,
            @Param("schoolId") Long schoolId,
            @Param("session") String session);

    // amountPaid and platformFee are both paise on every path that ever sets platformFee
    // non-zero (PaymentController.createOrder computes it in paise before persisting) — no
    // scaling needed. A prior version of this query multiplied platformFee by 100, assuming
    // rupees; that assumption predates the backend-authoritative checkout-quote rewrite and
    // was overcorrecting net collected by ~100x on any Razorpay payment (manual payments,
    // which always have platformFee=0, never exposed it). Confirmed against the platform-wide
    // SUPER_ADMIN equivalent below, which never had the *100 and was always correct.
    @Query("SELECT COALESCE(SUM(p.amountPaid - p.platformFee), 0) FROM Payment p WHERE p.schoolId = :schoolId AND EXTRACT(MONTH FROM p.paymentDate) = :month AND EXTRACT(YEAR FROM p.paymentDate) = :year")
    long sumAmountCollectedBySchoolIdAndMonthAndYear(@Param("schoolId") Long schoolId, @Param("month") int month, @Param("year") int year);

    List<Payment> findBySchoolIdAndPaymentDateAfter(Long schoolId, LocalDateTime since);

    // Platform-wide (SUPER_ADMIN dashboard — across all schools)
    @Query("SELECT COALESCE(SUM(p.amountPaid - p.platformFee), 0) FROM Payment p WHERE EXTRACT(MONTH FROM p.paymentDate) = :month AND EXTRACT(YEAR FROM p.paymentDate) = :year")
    long sumAmountCollectedByMonthAndYear(@Param("month") int month, @Param("year") int year);

    List<Payment> findBySchoolId(Long schoolId);
}
