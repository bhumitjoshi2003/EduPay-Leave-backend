package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.FeeStructure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FeeStructureRepository extends JpaRepository<FeeStructure, Long> {

    List<FeeStructure> findBySchoolId(Long schoolId);

    List<FeeStructure> findByAcademicYearAndSchoolId(String academicYear, Long schoolId);

    FeeStructure findByAcademicYearAndClassNameAndSchoolId(String academicYear, String className, Long schoolId);

    void deleteByAcademicYearAndSchoolId(String academicYear, Long schoolId);
}
