package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.Invoice;
import com.indraacademy.ias_management.entity.InvoiceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    Optional<Invoice> findByIdAndSchoolId(Long id, Long schoolId);

    Optional<Invoice> findBySchoolIdAndInvoiceNumber(Long schoolId, String invoiceNumber);

    List<Invoice> findBySchoolIdAndStudentIdAndAcademicSessionId(
            Long schoolId, String studentId, Long academicSessionId);

    /** Check if invoice already exists for this student/month/session (idempotency) */
    boolean existsBySchoolIdAndStudentIdAndAcademicSessionIdAndBillingMonth(
            Long schoolId, String studentId, Long academicSessionId, int billingMonth);

    /** All unpaid/overdue invoices for a student */
    @Query("SELECT i FROM Invoice i WHERE i.schoolId = :schoolId AND i.studentId = :studentId " +
            "AND i.status IN ('ISSUED', 'PARTIALLY_PAID', 'OVERDUE') " +
            "ORDER BY i.billingMonth ASC")
    List<Invoice> findOutstandingByStudent(
            @Param("schoolId") Long schoolId,
            @Param("studentId") String studentId);

    /** Paginated invoice list for admin with optional filters */
    @Query("SELECT i FROM Invoice i WHERE i.schoolId = :schoolId " +
            "AND (:studentId IS NULL OR i.studentId = :studentId) " +
            "AND (:sessionId IS NULL OR i.academicSession.id = :sessionId) " +
            "AND (:status IS NULL OR i.status = :status)")
    Page<Invoice> findFiltered(
            @Param("schoolId") Long schoolId,
            @Param("studentId") String studentId,
            @Param("sessionId") Long sessionId,
            @Param("status") InvoiceStatus status,
            Pageable pageable);

    /** Count overdue invoices for dashboard */
    @Query("SELECT COUNT(DISTINCT i.studentId) FROM Invoice i " +
            "WHERE i.schoolId = :schoolId AND i.status = 'OVERDUE' " +
            "AND i.academicSession.id = :sessionId")
    long countDistinctOverdueStudents(
            @Param("schoolId") Long schoolId,
            @Param("sessionId") Long sessionId);

    /** Sum of outstanding balances for a school */
    @Query("SELECT COALESCE(SUM(i.balanceDue), 0) FROM Invoice i " +
            "WHERE i.schoolId = :schoolId " +
            "AND i.status IN ('ISSUED', 'PARTIALLY_PAID', 'OVERDUE') " +
            "AND i.academicSession.id = :sessionId")
    long sumOutstandingBalance(
            @Param("schoolId") Long schoolId,
            @Param("sessionId") Long sessionId);

    /** Get the latest invoice number for auto-increment */
    @Query("SELECT MAX(i.invoiceNumber) FROM Invoice i WHERE i.schoolId = :schoolId")
    Optional<String> findMaxInvoiceNumber(@Param("schoolId") Long schoolId);

    /** All DRAFT invoices for a session (for bulk review/issue) */
    List<Invoice> findBySchoolIdAndAcademicSessionIdAndStatus(
            Long schoolId, Long academicSessionId, InvoiceStatus status);

    /** Check if any invoices exist for a given session (used before session deletion) */
    boolean existsBySchoolIdAndAcademicSessionId(Long schoolId, Long academicSessionId);

    /** All students with invoices for a session + month (for generation idempotency) */
    @Query("SELECT DISTINCT i.studentId FROM Invoice i " +
            "WHERE i.schoolId = :schoolId " +
            "AND i.academicSession.id = :sessionId " +
            "AND i.billingMonth = :billingMonth")
    List<String> findStudentsWithInvoice(
            @Param("schoolId") Long schoolId,
            @Param("sessionId") Long sessionId,
            @Param("billingMonth") int billingMonth);
}
