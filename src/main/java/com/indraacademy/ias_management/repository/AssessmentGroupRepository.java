package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.AssessmentGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AssessmentGroupRepository extends JpaRepository<AssessmentGroup, Long> {

    List<AssessmentGroup> findBySessionAndClassNameAndSchoolIdOrderByDisplayOrderAsc(
            String session, String className, Long schoolId);

    List<AssessmentGroup> findBySessionAndSchoolIdOrderByDisplayOrderAsc(
            String session, Long schoolId);

    Optional<AssessmentGroup> findByIdAndSchoolId(Long id, Long schoolId);

    boolean existsBySessionAndClassNameAndNameAndSchoolId(
            String session, String className, String name, Long schoolId);

    List<AssessmentGroup> findByClassNameAndSchoolId(String className, Long schoolId);
}
