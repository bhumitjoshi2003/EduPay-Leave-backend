package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.Assessment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AssessmentRepository extends JpaRepository<Assessment, Long> {

    Optional<Assessment> findByIdAndSchoolId(Long id, Long schoolId);

    boolean existsByAttachmentObjectKey(String attachmentObjectKey);

    // ─── Manage (admin: whole school; teacher: own + classes they relate to, filtered in the service) ──

    String TEACHER_CANDIDATES = "a.schoolId = :schoolId AND a.academicSessionId = :sessionId "
            + "AND (a.createdByUserId = :userId OR a.classId IN :classIds) ";

    /** Coarse pre-filter (own, or any class the teacher relates to); AssessmentService applies the exact relevance rule. */
    @Query("SELECT a FROM Assessment a WHERE " + TEACHER_CANDIDATES
            + "AND a.assessmentDate >= :from ORDER BY a.assessmentDate ASC, a.startTime ASC, a.id ASC")
    List<Assessment> findTeacherCandidatesFrom(@Param("schoolId") Long schoolId, @Param("sessionId") Long sessionId,
                                               @Param("userId") String userId, @Param("classIds") Collection<Long> classIds,
                                               @Param("from") LocalDate from, Pageable pageable);

    @Query("SELECT a FROM Assessment a WHERE " + TEACHER_CANDIDATES
            + "AND a.assessmentDate < :before ORDER BY a.assessmentDate DESC, a.id DESC")
    List<Assessment> findTeacherCandidatesBefore(@Param("schoolId") Long schoolId, @Param("sessionId") Long sessionId,
                                                 @Param("userId") String userId, @Param("classIds") Collection<Long> classIds,
                                                 @Param("before") LocalDate before, Pageable pageable);

    List<Assessment> findBySchoolIdAndAcademicSessionIdAndAssessmentDateGreaterThanEqualOrderByAssessmentDateAscStartTimeAscIdAsc(
            Long schoolId, Long sessionId, LocalDate from, Pageable pageable);

    List<Assessment> findBySchoolIdAndAcademicSessionIdAndAssessmentDateBeforeOrderByAssessmentDateDescIdDesc(
            Long schoolId, Long sessionId, LocalDate before, Pageable pageable);

    // ─── Student: their class — whole-class assessments plus their own section's ──

    String CLASS_SCOPE = "a.schoolId = :schoolId AND a.academicSessionId = :sessionId AND a.classId = :classId "
            + "AND (a.sectionId IS NULL OR a.sectionId = :sectionId) ";

    @Query("SELECT a FROM Assessment a WHERE " + CLASS_SCOPE
            + "AND a.assessmentDate >= :from ORDER BY a.assessmentDate ASC, a.startTime ASC, a.id ASC")
    List<Assessment> findForClassFrom(@Param("schoolId") Long schoolId, @Param("sessionId") Long sessionId,
                                      @Param("classId") Long classId, @Param("sectionId") Long sectionId,
                                      @Param("from") LocalDate from, Pageable pageable);

    @Query("SELECT a FROM Assessment a WHERE " + CLASS_SCOPE
            + "AND a.assessmentDate >= :from AND a.assessmentDate <= :to "
            + "ORDER BY a.assessmentDate ASC, a.startTime ASC, a.id ASC")
    List<Assessment> findForClassBetween(@Param("schoolId") Long schoolId, @Param("sessionId") Long sessionId,
                                         @Param("classId") Long classId, @Param("sectionId") Long sectionId,
                                         @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT a FROM Assessment a WHERE " + CLASS_SCOPE
            + "AND a.assessmentDate < :before ORDER BY a.assessmentDate DESC, a.id DESC")
    List<Assessment> findForClassBefore(@Param("schoolId") Long schoolId, @Param("sessionId") Long sessionId,
                                        @Param("classId") Long classId, @Param("sectionId") Long sectionId,
                                        @Param("before") LocalDate before, Pageable pageable);

    // ─── Reminders ──────────────────────────────────────────────────────

    /**
     * Not-yet-reminded assessments dated within [from, to] across all schools, oldest first. The
     * window is deliberately wider than "tomorrow" so every school's local tomorrow is covered;
     * the scheduler decides per school whether each one is due.
     */
    @Query("SELECT a FROM Assessment a WHERE a.assessmentDate >= :from AND a.assessmentDate <= :to "
            + "AND a.reminderSentAt IS NULL ORDER BY a.id ASC")
    List<Assessment> findReminderCandidates(@Param("from") LocalDate from, @Param("to") LocalDate to, Pageable pageable);

    /**
     * Marks the reminder as sent only if it still applies: same date, not already sent. Returns 0
     * when the assessment was deleted, rescheduled or reminded concurrently in the meantime.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Assessment a SET a.reminderSentAt = :sentAt WHERE a.id = :id "
            + "AND a.assessmentDate = :date AND a.reminderSentAt IS NULL")
    int markReminderSent(@Param("id") Long id, @Param("date") LocalDate date, @Param("sentAt") Instant sentAt);

    /** Undoes this pass's own claim (matched by its timestamp) when publishing failed, so a later pass retries. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Assessment a SET a.reminderSentAt = NULL WHERE a.id = :id AND a.reminderSentAt = :sentAt")
    int releaseReminderClaim(@Param("id") Long id, @Param("sentAt") Instant sentAt);
}
