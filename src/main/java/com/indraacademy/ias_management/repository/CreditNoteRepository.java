package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.CreditNote;
import com.indraacademy.ias_management.entity.CreditNoteStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CreditNoteRepository extends JpaRepository<CreditNote, Long> {

    Optional<CreditNote> findByIdAndSchoolId(Long id, Long schoolId);

    List<CreditNote> findBySchoolIdAndStudentId(Long schoolId, String studentId);

    Page<CreditNote> findBySchoolIdAndStatus(Long schoolId, CreditNoteStatus status, Pageable pageable);

    Page<CreditNote> findBySchoolId(Long schoolId, Pageable pageable);
}
