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

    // Platform-wide lookup (used by schedulers — no schoolId filter)
    Optional<Student> findByStudentId(String studentId);

    List<Student> findByClassNameAndSchoolId(String className, Long schoolId);

    List<Student> findByClassNameAndStatusAndSchoolId(String className, StudentStatus status, Long schoolId);

    List<Student> findByClassNameAndSectionIdAndStatusAndSchoolId(String className, Long sectionId, StudentStatus status, Long schoolId);

    List<Student> findByClassNameAndSectionIdAndSchoolId(String className, Long sectionId, Long schoolId);

    long countBySchoolIdAndSectionId(Long schoolId, Long sectionId);

    @Modifying
    @Query("UPDATE Student s SET s.sectionId = null, s.sectionName = null WHERE s.schoolId = :schoolId AND s.sectionId = :sectionId")
    void clearSectionBySchoolAndSectionId(@Param("schoolId") Long schoolId, @Param("sectionId") Long sectionId);

    /**
     * Clears section data for students whose sectionId belongs to a different class
     * than the student's current class (orphaned after promotion without section resolution).
     */
    @Modifying
    @Query("UPDATE Student s SET s.sectionId = null, s.sectionName = null " +
           "WHERE s.schoolId = :schoolId AND s.sectionId IS NOT NULL " +
           "AND s.sectionId NOT IN (" +
           "  SELECT sec.id FROM Section sec WHERE sec.classId = s.classId" +
           ")")
    int clearOrphanedSections(@Param("schoolId") Long schoolId);

    // Platform-wide status lookup (used by scheduler — no schoolId filter)
    List<Student> findByStatus(StudentStatus status);

    List<Student> findByStatusAndSchoolId(StudentStatus status, Long schoolId);

    List<Student> findBySchoolId(Long schoolId);

    Optional<Student> findByStudentIdAndSchoolId(String studentId, Long schoolId);

    List<Student> findByStudentIdInAndSchoolId(List<String> studentIds, Long schoolId);

    @Query("SELECT s FROM Student s WHERE s.schoolId = :schoolId " +
           "AND (LOWER(s.name) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR LOWER(s.studentId) LIKE LOWER(CONCAT('%', :query, '%'))) " +
           "ORDER BY s.name")
    List<Student> searchByNameOrIdAndSchoolId(@Param("query") String query, @Param("schoolId") Long schoolId);

    long countByStatusAndSchoolId(StudentStatus status, Long schoolId);
    long countByClassNameAndSchoolId(String className, Long schoolId);

    @Query("SELECT DISTINCT s.className FROM Student s WHERE s.status = 'ACTIVE' AND s.schoolId = :schoolId ORDER BY s.className")
    List<String> findDistinctActiveClassNamesBySchoolId(@Param("schoolId") Long schoolId);

    // Platform-wide status updates (run by scheduler across all schools).
    // Exit statuses (GRADUATED, TRANSFERRED, WITHDRAWN) are NEVER overwritten.
    @Modifying
    @Query("UPDATE Student s SET s.status = 'UPCOMING' WHERE s.joiningDate > :today " +
           "AND s.status NOT IN ('GRADUATED', 'TRANSFERRED', 'WITHDRAWN')")
    void updateStatusUpcoming(@Param("today") LocalDate today);

    @Modifying
    @Query("UPDATE Student s SET s.status = 'ACTIVE' WHERE (s.joiningDate IS NULL OR s.joiningDate <= :today) " +
           "AND (s.leavingDate IS NULL OR s.leavingDate > :today) " +
           "AND s.status NOT IN ('GRADUATED', 'TRANSFERRED', 'WITHDRAWN')")
    void updateStatusActive(@Param("today") LocalDate today);

    // Query for "Left" tab — TRANSFERRED + WITHDRAWN students
    List<Student> findByClassNameAndStatusInAndSchoolId(String className, java.util.List<StudentStatus> statuses, Long schoolId);

    List<Student> findByClassNameAndSectionIdAndStatusInAndSchoolId(String className, Long sectionId, java.util.List<StudentStatus> statuses, Long schoolId);
}
