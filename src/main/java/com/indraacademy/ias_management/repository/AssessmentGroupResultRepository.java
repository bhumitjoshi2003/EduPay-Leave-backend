package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.AssessmentGroupResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface AssessmentGroupResultRepository extends JpaRepository<AssessmentGroupResult, Long> {

    Optional<AssessmentGroupResult> findByStudentIdAndAssessmentGroupIdAndSession(
            String studentId, Long assessmentGroupId, String session);

    List<AssessmentGroupResult> findByAssessmentGroupIdAndSessionOrderByWeightedScoreDesc(
            Long assessmentGroupId, String session);

    @Transactional
    void deleteByAssessmentGroupIdAndSession(Long assessmentGroupId, String session);
}
