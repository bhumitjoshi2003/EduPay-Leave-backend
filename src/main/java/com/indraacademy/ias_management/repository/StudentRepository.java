package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface StudentRepository extends JpaRepository<Student, String> {

    // Platform-wide lookups (used by schedulers/controllers — no schoolId filter)
    List<Student> findByClassName(String className);

    Optional<Student> findByStudentId(String studentId);

    List<Student> findByClassNameAndSchoolId(String className, Long schoolId);

    List<Student> findByClassNameAndStatusAndSchoolId(String className, StudentStatus status, Long schoolId);

    // Platform-wide status lookup (used by scheduler — no schoolId filter)
    List<Student> findByStatus(StudentStatus status);

    List<Student> findByStatusAndSchoolId(StudentStatus status, Long schoolId);

    List<Student> findBySchoolId(Long schoolId);

    Optional<Student> findByStudentIdAndSchoolId(String studentId, Long schoolId);

    long countByStatusAndSchoolId(StudentStatus status, Long schoolId);

    @Query("SELECT DISTINCT s.className FROM Student s WHERE s.status = 'ACTIVE' AND s.schoolId = :schoolId ORDER BY s.className")
    List<String> findDistinctActiveClassNamesBySchoolId(@Param("schoolId") Long schoolId);

    // Platform-wide status updates (run by scheduler across all schools)
    @Modifying
    @Query("UPDATE Student s SET s.status = 'UPCOMING' WHERE s.joiningDate > :today")
    void updateStatusUpcoming(@Param("today") LocalDate today);

    @Modifying
    @Query("UPDATE Student s SET s.status = 'INACTIVE' WHERE s.leavingDate IS NOT NULL AND s.leavingDate <= :today")
    void updateStatusInactive(@Param("today") LocalDate today);

    @Modifying
    @Query("UPDATE Student s SET s.status = 'ACTIVE' WHERE (s.joiningDate IS NULL OR s.joiningDate <= :today) AND (s.leavingDate IS NULL OR s.leavingDate > :today)")
    void updateStatusActive(@Param("today") LocalDate today);
}
