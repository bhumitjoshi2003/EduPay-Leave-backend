package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.StudentFeesLineItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Read/append-only in practice — see StudentFeesLineItem's class Javadoc for the
 * immutability guarantee this repository's callers must uphold (create, never update or
 * delete an existing row). */
@Repository
public interface StudentFeesLineItemRepository extends JpaRepository<StudentFeesLineItem, Long> {

    /** ALL line items ever written for this row, including any Phase 5A-superseded ones —
     * the full historical record. Read paths that display "the current bill" must use
     * {@link #findByStudentFeesIdAndSupersededAtIsNullOrderById} instead. */
    List<StudentFeesLineItem> findByStudentFeesIdOrderById(Long studentFeesId);

    /** The current, authoritative line items only (supersededAt IS NULL) — what every
     * display/receipt/reconciliation read path must use. */
    List<StudentFeesLineItem> findByStudentFeesIdAndSupersededAtIsNullOrderById(Long studentFeesId);

    boolean existsByStudentFeesId(Long studentFeesId);
}
