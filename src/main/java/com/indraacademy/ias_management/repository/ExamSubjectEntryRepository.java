package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.ExamSubjectEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExamSubjectEntryRepository extends JpaRepository<ExamSubjectEntry, Long> {

    List<ExamSubjectEntry> findByExamConfigId(Long examConfigId);

    Optional<ExamSubjectEntry> findByIdAndSchoolId(Long id, Long schoolId);

    boolean existsByExamConfigIdAndSubjectName(Long examConfigId, String subjectName);

    @Transactional
    void deleteByExamConfigId(Long examConfigId);
}
