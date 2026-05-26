package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.StudentFeeConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface StudentFeeConfigRepository extends JpaRepository<StudentFeeConfig, Long> {

    List<StudentFeeConfig> findBySchoolIdAndStudentIdAndAcademicSessionId(
            Long schoolId, String studentId, Long academicSessionId);

    /**
     * Find active configs for a student on a given date.
     */
    @Query("SELECT c FROM StudentFeeConfig c WHERE c.schoolId = :schoolId " +
            "AND c.studentId = :studentId AND c.academicSession.id = :sessionId " +
            "AND (c.validFrom IS NULL OR c.validFrom <= :asOfDate) " +
            "AND (c.validUntil IS NULL OR c.validUntil >= :asOfDate)")
    List<StudentFeeConfig> findActiveConfigs(
            @Param("schoolId") Long schoolId,
            @Param("studentId") String studentId,
            @Param("sessionId") Long sessionId,
            @Param("asOfDate") LocalDate asOfDate);

    void deleteBySchoolIdAndStudentIdAndAcademicSessionId(
            Long schoolId, String studentId, Long academicSessionId);
}
