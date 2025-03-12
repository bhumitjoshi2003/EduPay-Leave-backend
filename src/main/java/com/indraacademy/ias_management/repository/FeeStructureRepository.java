package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.FeeStructure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FeeStructureRepository extends JpaRepository<FeeStructure, Long> {

    List<FeeStructure> findByAcademicYear(String academicYear);

    void deleteByAcademicYear(String academicYear);
}