package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.StudentFeeConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
            "AND c.revokedAt IS NULL " +
            "AND (c.validFrom IS NULL OR c.validFrom <= :asOfDate) " +
            "AND (c.validUntil IS NULL OR c.validUntil >= :asOfDate)")
    List<StudentFeeConfig> findActiveConfigs(
            @Param("schoolId") Long schoolId,
            @Param("studentId") String studentId,
            @Param("sessionId") Long sessionId,
            @Param("asOfDate") LocalDate asOfDate);

    @Query("SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END FROM StudentFeeConfig c " +
            "WHERE c.schoolId = :schoolId AND c.studentId = :studentId " +
            "AND c.academicSession.id = :sessionId AND c.feeHead.id = :feeHeadId " +
            "AND c.revokedAt IS NULL " +
            "AND (c.validUntil IS NULL OR c.validUntil >= :validFrom) " +
            "AND (CAST(:validUntil AS date) IS NULL OR c.validFrom IS NULL OR c.validFrom <= :validUntil)")
    boolean existsOverlapping(@Param("schoolId") Long schoolId,
                              @Param("studentId") String studentId,
                              @Param("sessionId") Long sessionId,
                              @Param("feeHeadId") Long feeHeadId,
                              @Param("validFrom") LocalDate validFrom,
                              @Param("validUntil") LocalDate validUntil);

    @Query("SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END FROM StudentFeeConfig c " +
            "WHERE c.id <> :configId AND c.schoolId = :schoolId AND c.studentId = :studentId " +
            "AND c.academicSession.id = :sessionId AND c.feeHead.id = :feeHeadId AND c.revokedAt IS NULL " +
            "AND (c.validUntil IS NULL OR c.validUntil >= :validFrom) " +
            "AND (CAST(:validUntil AS date) IS NULL OR c.validFrom IS NULL OR c.validFrom <= :validUntil)")
    boolean existsOverlappingExcluding(@Param("configId") Long configId,
                                       @Param("schoolId") Long schoolId,
                                       @Param("studentId") String studentId,
                                       @Param("sessionId") Long sessionId,
                                       @Param("feeHeadId") Long feeHeadId,
                                       @Param("validFrom") LocalDate validFrom,
                                       @Param("validUntil") LocalDate validUntil);

    List<StudentFeeConfig> findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByValidFromDescIdDesc(
            Long schoolId, String studentId, Long academicSessionId);

    @Modifying
    @Query("DELETE FROM StudentFeeConfig c WHERE c.schoolId = :schoolId AND c.studentId = :studentId AND c.academicSession.id = :sessionId")
    void deleteBySchoolIdAndStudentIdAndAcademicSessionId(
            @Param("schoolId") Long schoolId, @Param("studentId") String studentId, @Param("sessionId") Long sessionId);

    @Modifying
    @Query("DELETE FROM StudentFeeConfig c WHERE c.schoolId = :schoolId AND c.academicSession.id = :sessionId")
    void deleteBySchoolIdAndAcademicSessionId(@Param("schoolId") Long schoolId, @Param("sessionId") Long sessionId);
}
