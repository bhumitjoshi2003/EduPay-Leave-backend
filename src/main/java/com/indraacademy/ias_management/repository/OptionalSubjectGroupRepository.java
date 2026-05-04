package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.OptionalSubjectGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OptionalSubjectGroupRepository extends JpaRepository<OptionalSubjectGroup, Long> {

    List<OptionalSubjectGroup> findBySchoolId(Long schoolId);
}
