package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.StudentTransportFeeAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface StudentTransportFeeAssignmentRepository extends JpaRepository<StudentTransportFeeAssignment, Long> {
    List<StudentTransportFeeAssignment> findBySchoolIdAndStudentIdAndAcademicSessionOrderByEffectiveFromDesc(
            Long schoolId, String studentId, String academicSession);

    @Query("SELECT t FROM StudentTransportFeeAssignment t WHERE t.schoolId=:schoolId AND t.studentId=:studentId " +
           "AND t.academicSession=:session AND t.effectiveFrom<=:date AND (t.effectiveTo IS NULL OR t.effectiveTo>=:date) " +
           "ORDER BY t.effectiveFrom DESC")
    List<StudentTransportFeeAssignment> findEffective(@Param("schoolId") Long schoolId,
            @Param("studentId") String studentId, @Param("session") String session, @Param("date") LocalDate date);

    default Optional<StudentTransportFeeAssignment> effectiveOn(Long schoolId, String studentId, String session, LocalDate date) {
        return findEffective(schoolId, studentId, session, date).stream().findFirst();
    }

    Optional<StudentTransportFeeAssignment> findByIdAndSchoolId(Long id, Long schoolId);
}
