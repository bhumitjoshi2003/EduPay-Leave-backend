package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.FeeGenerationBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FeeGenerationBatchRepository extends JpaRepository<FeeGenerationBatch, Long> {
    List<FeeGenerationBatch> findTop25BySchoolIdAndAcademicSessionOrderByStartedAtDesc(Long schoolId, String academicSession);
    Optional<FeeGenerationBatch> findByIdAndSchoolId(Long id, Long schoolId);
}
