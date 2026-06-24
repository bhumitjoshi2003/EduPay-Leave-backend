package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.AssessmentGroupComposition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface AssessmentGroupCompositionRepository extends JpaRepository<AssessmentGroupComposition, Long> {

    List<AssessmentGroupComposition> findByParentGroupIdAndSchoolIdOrderByDisplayOrderAsc(
            Long parentGroupId, Long schoolId);

    List<AssessmentGroupComposition> findByParentGroupIdOrderByDisplayOrderAsc(Long parentGroupId);

    @Transactional
    void deleteByParentGroupIdAndSchoolId(Long parentGroupId, Long schoolId);
}
