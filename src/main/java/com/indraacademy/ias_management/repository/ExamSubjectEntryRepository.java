package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.ExamSubjectEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExamSubjectEntryRepository extends JpaRepository<ExamSubjectEntry, Long> {

    List<ExamSubjectEntry> findByExamConfigIdAndSchoolId(Long examConfigId, Long schoolId);

    Optional<ExamSubjectEntry> findByIdAndSchoolId(Long id, Long schoolId);

    boolean existsByExamConfigIdAndSubjectNameAndSchoolId(Long examConfigId, String subjectName, Long schoolId);

    @Transactional
    void deleteByExamConfigIdAndSchoolId(Long examConfigId, Long schoolId);
}
