package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.StudentFeeAssignment;
import com.indraacademy.ias_management.entity.StudentFeeAssignmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface StudentFeeAssignmentRepository extends JpaRepository<StudentFeeAssignment, Long> {
    Optional<StudentFeeAssignment> findBySchoolIdAndStudentIdAndAcademicSession(Long schoolId, String studentId, String academicSession);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM StudentFeeAssignment a WHERE a.schoolId = :schoolId AND a.studentId = :studentId AND a.academicSession = :session")
    Optional<StudentFeeAssignment> findForGenerationUpdate(@Param("schoolId") Long schoolId,
            @Param("studentId") String studentId, @Param("session") String session);
    List<StudentFeeAssignment> findBySchoolIdAndAcademicSession(Long schoolId, String academicSession);
    long countBySchoolIdAndAcademicSessionAndStatus(Long schoolId, String academicSession, StudentFeeAssignmentStatus status);
}
