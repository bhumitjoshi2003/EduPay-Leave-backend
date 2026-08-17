package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.Leave;
import com.indraacademy.ias_management.entity.LeaveStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

@Repository
public interface LeaveRepository extends JpaRepository<Leave, Long> {

    void deleteByStudentIdAndLeaveDateAndSchoolId(String studentId, String leaveDate, Long schoolId);

    @org.springframework.transaction.annotation.Transactional
    void deleteByStudentIdAndSchoolId(String studentId, Long schoolId);

    Page<Leave> findByStudentIdContainingAndSchoolId(String studentId, Long schoolId, Pageable pageable);

    Page<Leave> findByLeaveDateAndSchoolId(String leaveDate, Long schoolId, Pageable pageable);

    Page<Leave> findByStudentIdContainingAndLeaveDateAndSchoolId(String studentId, String leaveDate, Long schoolId, Pageable pageable);

    Page<Leave> findByClassNameAndStudentIdContainingAndLeaveDateAndSchoolId(String className, String studentId, String leaveDate, Long schoolId, Pageable pageable);

    Page<Leave> findByClassNameAndStudentIdContainingAndSchoolId(String className, String studentId, Long schoolId, Pageable pageable);

    Page<Leave> findByClassNameAndLeaveDateAndSchoolId(String className, String leaveDate, Long schoolId, Pageable pageable);

    Page<Leave> findByClassNameAndSchoolId(String className, Long schoolId, Pageable pageable);

    Page<Leave> findByStudentIdAndSchoolId(String studentId, Long schoolId, Pageable pageable);

    @Query("SELECT l.studentId FROM Leave l WHERE l.leaveDate = :date AND l.className = :className AND l.schoolId = :schoolId")
    List<String> findByLeaveDateAndClassNameAndSchoolId(@Param("date") String date, @Param("className") String className, @Param("schoolId") Long schoolId);

    Leave findByStudentIdAndLeaveDateAndSchoolId(String studentId, String leaveDate, Long schoolId);

    long countBySchoolIdAndAppliedDateBetween(Long schoolId, java.time.LocalDateTime start, java.time.LocalDateTime end);

    long countByStatusAndSchoolIdAndAppliedDateBetween(LeaveStatus status, Long schoolId, java.time.LocalDateTime start, java.time.LocalDateTime end);

    long countByStatusAndSchoolId(LeaveStatus status, Long schoolId);

    List<Leave> findBySchoolId(Long schoolId);

    /**
     * Leave requests awaiting a decision, oldest first — the review queue backing the AI Copilot's
     * read-only leave tools. Null className/studentId mean "no filter"; a Pageable caps the size.
     * Deliberately status-aware, which getLeavesFiltered is not.
     */
    @Query("SELECT l FROM Leave l WHERE l.schoolId = :schoolId AND l.status = :status " +
           "AND (:className IS NULL OR l.className = :className) " +
           "AND (:studentId IS NULL OR l.studentId = :studentId) " +
           "ORDER BY l.appliedDate ASC")
    List<Leave> findForReview(@Param("status") LeaveStatus status,
                              @Param("schoolId") Long schoolId,
                              @Param("className") String className,
                              @Param("studentId") String studentId,
                              Pageable pageable);

    /** Resolves specific leave ids within the caller's school, whatever their current status. */
    List<Leave> findByIdInAndSchoolId(List<Long> ids, Long schoolId);

    /**
     * Row-locked read for {@code LeaveService.updateLeaveStatus}'s check-then-update — the same
     * PESSIMISTIC_WRITE-locked-finder pattern already used by every AI reminder batch repository
     * (see e.g. AiFeeReminderBatchRepository.findByWorkflowIdForUpdate).
     *
     * <p>Two admins deciding the same leave at nearly the same moment now serialize on this lock:
     * whichever call arrives second blocks until the first commits, then re-reads the row it just
     * blocked on — seeing the first caller's decision rather than a stale copy taken before either
     * of them acted. That is what makes the "already decided" check in updateLeaveStatus race-free
     * instead of merely likely-correct.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from Leave l where l.id = :leaveId")
    Optional<Leave> findByIdForUpdate(@Param("leaveId") Long leaveId);
}
