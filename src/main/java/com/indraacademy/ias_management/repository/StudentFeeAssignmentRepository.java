package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.StudentFeeAssignment;
import com.indraacademy.ias_management.entity.StudentFeeAssignmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StudentFeeAssignmentRepository extends JpaRepository<StudentFeeAssignment, Long> {
    Optional<StudentFeeAssignment> findBySchoolIdAndStudentIdAndAcademicSession(Long schoolId, String studentId, String academicSession);
    List<StudentFeeAssignment> findBySchoolIdAndAcademicSession(Long schoolId, String academicSession);
    long countBySchoolIdAndAcademicSessionAndStatus(Long schoolId, String academicSession, StudentFeeAssignmentStatus status);
}

