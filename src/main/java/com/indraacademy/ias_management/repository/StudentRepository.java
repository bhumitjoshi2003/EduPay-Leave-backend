package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;

@Repository
public interface StudentRepository extends JpaRepository<Student, String> {

    List<Student> findByClassNameAndSchoolId(String className, Long schoolId);

    List<Student> findByClassNameAndStatusAndSchoolId(String className, StudentStatus status, Long schoolId);

    List<Student> findByClassNameAndSectionIdAndStatusAndSchoolId(String className, Long sectionId, StudentStatus status, Long schoolId);

    List<Student> findByClassNameAndSectionIdAndSchoolId(String className, Long sectionId, Long schoolId);

    long countBySchoolIdAndSectionId(Long schoolId, Long sectionId);

    @Modifying
    @Query("UPDATE Student s SET s.sectionId = null, s.sectionName = null " +
           "WHERE s.schoolId = :schoolId AND s.sectionId = :sectionId " +
           "AND NOT EXISTS (SELECT e.id FROM StudentEnrollment e " +
           "  WHERE e.schoolId = s.schoolId AND e.studentId = s.studentId)")
    int clearSectionBySchoolAndSectionId(@Param("schoolId") Long schoolId, @Param("sectionId") Long sectionId);

    /**
     * Clears section data for students whose sectionId belongs to a different class
     * than the student's current class (orphaned after promotion without section resolution).
     */
    @Modifying
    @Query("UPDATE Student s SET s.sectionId = null, s.sectionName = null " +
           "WHERE s.schoolId = :schoolId AND s.sectionId IS NOT NULL " +
           "AND s.sectionId NOT IN (" +
           "  SELECT sec.id FROM Section sec WHERE sec.classId = s.classId" +
           ") AND NOT EXISTS (SELECT e.id FROM StudentEnrollment e " +
           "  WHERE e.schoolId = s.schoolId AND e.studentId = s.studentId)")
    int clearOrphanedSections(@Param("schoolId") Long schoolId);

    // Platform-wide status lookup (used by scheduler — no schoolId filter)
    List<Student> findByStatus(StudentStatus status);

    List<Student> findByStatusAndSchoolId(StudentStatus status, Long schoolId);

    List<Student> findBySchoolId(Long schoolId);

    Optional<Student> findByStudentIdAndSchoolId(String studentId, Long schoolId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Student s WHERE s.studentId = :studentId AND s.schoolId = :schoolId")
    Optional<Student> findByStudentIdAndSchoolIdForUpdate(
            @Param("studentId") String studentId, @Param("schoolId") Long schoolId);

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

    List<Student> findBySchoolIdAndStatusAndJoiningDateLessThanEqualOrderByStudentId(
            Long schoolId, StudentStatus status, LocalDate joiningDate);

    // Query for "Left" tab — TRANSFERRED + WITHDRAWN students
    List<Student> findByClassNameAndStatusInAndSchoolId(String className, java.util.List<StudentStatus> statuses, Long schoolId);

    List<Student> findByClassNameAndSectionIdAndStatusInAndSchoolId(String className, Long sectionId, java.util.List<StudentStatus> statuses, Long schoolId);
}
