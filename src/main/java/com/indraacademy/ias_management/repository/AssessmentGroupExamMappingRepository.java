package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.AssessmentGroupExamMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface AssessmentGroupExamMappingRepository extends JpaRepository<AssessmentGroupExamMapping, Long> {

    List<AssessmentGroupExamMapping> findByAssessmentGroupIdAndSchoolIdOrderByDisplayOrderAsc(
            Long assessmentGroupId, Long schoolId);

    List<AssessmentGroupExamMapping> findByAssessmentGroupIdOrderByDisplayOrderAsc(Long assessmentGroupId);

    @Transactional
    void deleteByAssessmentGroupIdAndSchoolId(Long assessmentGroupId, Long schoolId);

    boolean existsByExamConfigIdAndSchoolId(Long examConfigId, Long schoolId);
}
