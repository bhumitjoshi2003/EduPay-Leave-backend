package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.entity.Leave;
import com.indraacademy.ias_management.entity.LeaveStatus;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.exception.InvalidLeaveStatusTransitionException;
import com.indraacademy.ias_management.notification.*;
import com.indraacademy.ias_management.repository.LeaveRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

@Service
public class LeaveService {

    private static final Logger log = LoggerFactory.getLogger(LeaveService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    @Autowired private LeaveRepository leaveRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private BusinessNotificationService businessNotifications;
    @Autowired private AuditService auditService;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TeacherClassScopeService teacherClassScopeService;

    @Transactional
    public void applyLeave(Leave leave, HttpServletRequest request){

        if (leave == null || leave.getStudentId() == null || leave.getLeaveDate() == null) {
            throw new IllegalArgumentException("Leave object and required fields must not be null.");
        }

        try {
            leave.setAppliedDate(LocalDateTime.now());
            leave.setSchoolId(securityUtil.getSchoolId());
            Leave savedLeave = leaveRepository.save(leave);

            // Audit
            auditService.log(
                    securityUtil.getUsername(),
                    securityUtil.getRole(),
                    "APPLY_LEAVE",
                    "Leave",
                    String.valueOf(savedLeave.getId()),
                    null,
                    objectMapper.writeValueAsString(savedLeave),
                    request.getRemoteAddr()
            );

            String studentMessage = String.format(
                    "Your leave application for %s has been submitted.",
                    savedLeave.getLeaveDate()
            );

            businessNotifications.studentAndParents(savedLeave.getSchoolId(), savedLeave.getStudentId(),
                    NotificationAudienceType.STUDENT_WITH_LEAVE_PARENTS, NotificationEventCode.LEAVE_SUBMITTED,
                    NotificationCategory.LEAVE, "Leave Applied", studentMessage, "Leave",
                    String.valueOf(savedLeave.getId()), "/dashboard/apply-leave", securityUtil.getUsername(),
                    "student-leave:" + savedLeave.getId() + ":submitted", Set.of(ExternalDeliveryChannel.PUSH));

        } catch (DataAccessException e) {
            throw new RuntimeException("Could not apply leave", e);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional
    public void deleteLeave(String studentId,
                            String leaveDate,
                            HttpServletRequest request) {

        if (studentId == null || studentId.trim().isEmpty()
                || leaveDate == null || leaveDate.trim().isEmpty()) {
            throw new IllegalArgumentException("Student ID and leave date must be provided.");
        }

        try {
            Long schoolId = securityUtil.getSchoolId();
            Leave leave = leaveRepository.findByStudentIdAndLeaveDateAndSchoolId(studentId, leaveDate, schoolId);

            if (leave == null) {
                throw new IllegalArgumentException("Leave not found for student " + studentId);
            }

            if (LeaveStatus.APPROVED.equals(leave.getStatus())) {
                throw new IllegalArgumentException("Cannot delete an approved leave application.");
            }

            String oldValue = objectMapper.writeValueAsString(leave);
            String leaveIdString = String.valueOf(leave.getId());

            leaveRepository.deleteByStudentIdAndLeaveDateAndSchoolId(studentId, leaveDate, schoolId);

            // Audit
            auditService.log(
                    securityUtil.getUsername(),
                    securityUtil.getRole(),
                    "DELETE_LEAVE",
                    "Leave",
                    leaveIdString,
                    oldValue,
                    null,
                    request.getRemoteAddr()
            );

            String studentMessage = String.format(
                    "Your leave application for %s has been deleted.",
                    leave.getLeaveDate()
            );

            publishStudentLeaveCancellation(leave, leaveIdString, studentMessage);

        } catch (DataAccessException e) {
            throw new RuntimeException("Could not delete leave", e);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional
    public void deleteLeaveById(Long leaveId,
                                HttpServletRequest request) {

        if (leaveId == null) {
            throw new IllegalArgumentException("Leave ID must be provided.");
        }

        try {
            Long schoolId = securityUtil.getSchoolId();
            Leave leave = leaveRepository.findById(leaveId)
                    .orElseThrow(() -> new IllegalArgumentException("Leave not found with ID " + leaveId));

            if (!schoolId.equals(leave.getSchoolId())) {
                throw new SecurityException("Access denied: leave does not belong to your school.");
            }

            String oldValue = objectMapper.writeValueAsString(leave);

            leaveRepository.deleteById(leaveId);

            // Audit
            auditService.log(
                    securityUtil.getUsername(),
                    securityUtil.getRole(),
                    "DELETE_LEAVE_BY_ID",
                    "Leave",
                    String.valueOf(leaveId),
                    oldValue,
                    null,
                    request.getRemoteAddr()
            );

            String studentMessage = String.format(
                    "Your leave application for %s has been deleted.",
                    leave.getLeaveDate()
            );

            publishStudentLeaveCancellation(leave, String.valueOf(leave.getId()), studentMessage);

        } catch (DataAccessException e) {
            throw new RuntimeException("Could not delete leave", e);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional(readOnly = true)
    public Optional<Leave> getLeaveById(Long leaveId) {
        if (leaveId == null) {
            log.warn("Attempted to get leave with null ID.");
            return Optional.empty();
        }
        log.info("Fetching leave by ID: {}", leaveId);
        try {
            Long schoolId = securityUtil.getSchoolId();
            return leaveRepository.findById(leaveId)
                    .filter(l -> schoolId.equals(l.getSchoolId()));
        } catch (DataAccessException e) {
            log.error("Data access error fetching leave ID: {}", leaveId, e);
            throw new RuntimeException("Could not retrieve leave due to data access issue", e);
        }
    }

    @Transactional
    public Leave updateLeaveStatus(Long leaveId, LeaveStatus status, HttpServletRequest request) {

        if (leaveId == null) {
            throw new IllegalArgumentException("Leave ID must be provided.");
        }
        if (status == null) {
            throw new IllegalArgumentException("Status must be provided.");
        }

        try {
            Long schoolId = securityUtil.getSchoolId();
            // Locked, not a plain findById: this is what makes the "already this status" check
            // below race-free rather than merely likely-correct. Two callers deciding the same
            // leave at nearly the same moment now serialize here — the second blocks until the
            // first commits, then re-reads the row it just blocked on and sees the first
            // caller's decision, instead of racing past it with a stale in-memory copy.
            // See LeaveRepository.findByIdForUpdate's Javadoc.
            Leave leave = leaveRepository.findByIdForUpdate(leaveId)
                    .orElseThrow(() -> new NoSuchElementException("Leave not found with ID " + leaveId));

            if (!schoolId.equals(leave.getSchoolId())) {
                throw new SecurityException("Access denied: leave does not belong to your school.");
            }

            // This endpoint previously had NO class/section check at all — any teacher could
            // approve/reject any other class's (or section's) leave request just by knowing the
            // leaveId. Scoped to the teacher's own class+section, same rule as everywhere else.
            if (Role.TEACHER.equals(securityUtil.getRole())) {
                Long studentSectionId = studentRepository.findByStudentIdAndSchoolId(leave.getStudentId(), schoolId)
                        .map(Student::getSectionId).orElse(null);
                TeacherClassScopeService.ScopedAccess access = teacherClassScopeService.authorizeAndScopeToStudent(
                        securityUtil.getRole(), securityUtil.getUsername(), schoolId, leave.getClassName(), studentSectionId);
                if (!access.allowed()) {
                    throw new SecurityException(access.errorMessage());
                }
            }

            // Only a genuine change is applied. A request whose target already matches the
            // leave's current status is a repeat — a double-click, a retried request, or a
            // second caller who raced to the same decision as the first — and is rejected rather
            // than silently re-applied, which would otherwise re-audit and re-notify the student
            // for a decision already made. This is deliberately narrower than "must be PENDING":
            // it does not block the product's existing APPROVED <-> REJECTED reversal action
            // (ViewLeavesComponent.editLeaveStatus), only a transition that changes nothing.
            if (status == leave.getStatus()) {
                throw new InvalidLeaveStatusTransitionException(leaveId, leave.getStatus(), status);
            }

            LeaveStatus previousStatus = leave.getStatus();
            String oldValue = objectMapper.writeValueAsString(leave);
            leave.setStatus(status);
            Leave updated = leaveRepository.save(leave);

            auditService.log(
                    securityUtil.getUsername(),
                    securityUtil.getRole(),
                    "UPDATE_LEAVE_STATUS",
                    "Leave",
                    String.valueOf(leaveId),
                    oldValue,
                    objectMapper.writeValueAsString(updated),
                    request.getRemoteAddr()
            );

            String studentMessage = String.format(
                    "Your leave application for %s has been %s.",
                    updated.getLeaveDate(),
                    status.name().toLowerCase()
            );

            NotificationEventCode eventCode = status == LeaveStatus.APPROVED
                    ? NotificationEventCode.LEAVE_APPROVED : NotificationEventCode.LEAVE_REJECTED;
            businessNotifications.studentAndParents(schoolId, updated.getStudentId(),
                    NotificationAudienceType.STUDENT_WITH_LEAVE_PARENTS, eventCode,
                    NotificationCategory.LEAVE, "Leave Status Updated", studentMessage, "Leave",
                    String.valueOf(updated.getId()), "/dashboard/apply-leave", securityUtil.getUsername(),
                    "student-leave:" + updated.getId() + ":decision:" + previousStatus + ":" + status,
                    Set.of(ExternalDeliveryChannel.PUSH));

            return updated;

        } catch (DataAccessException e) {
            throw new RuntimeException("Could not update leave status", e);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private void publishStudentLeaveCancellation(Leave leave, String leaveId, String message) {
        businessNotifications.studentAndParents(leave.getSchoolId(), leave.getStudentId(),
                NotificationAudienceType.STUDENT_WITH_LEAVE_PARENTS, NotificationEventCode.LEAVE_CANCELLED,
                NotificationCategory.LEAVE, "Leave Cancelled", message, "Leave", leaveId,
                "/dashboard/apply-leave", securityUtil.getUsername(),
                "student-leave:" + leaveId + ":cancelled", Set.of(ExternalDeliveryChannel.PUSH));
    }

    @Transactional(readOnly = true)
    public Page<Leave> getLeavesFiltered(String className, String studentId, String date, LeaveStatus status, Pageable pageable) {
        return getLeavesFiltered(className, studentId, date, status, null, pageable);
    }

    /** @param sectionId when non-null, restricts to leaves whose student is currently in that
     *  section — see LeaveRepository#findFilteredForManagement. */
    @Transactional(readOnly = true)
    public Page<Leave> getLeavesFiltered(String className, String studentId, String date, LeaveStatus status, Long sectionId, Pageable pageable) {
        log.info("Filtering leaves. Class: {}, Student ID: {}, Date: {}, Status: {}, Section: {}", className, studentId, date, status, sectionId);
        Long schoolId = securityUtil.getSchoolId();
        try {
            return leaveRepository.findFilteredForManagement(schoolId, className, studentId, date, status, sectionId, pageable);
        } catch (DataAccessException e) {
            log.error("Data access error during getLeavesFiltered. Class: {}, Student ID: {}, Date: {}, Status: {}", className, studentId, date, status, e);
            throw new RuntimeException("Could not retrieve filtered leaves due to data access issue", e);
        }
    }

    @Transactional(readOnly = true)
    public Page<Leave> getLeavesByStudentId(String studentId, Pageable pageable) {
        if (studentId == null || studentId.trim().isEmpty()) {
            log.warn("Attempted to get leaves with null/empty student ID.");
            return Page.empty(pageable);
        }
        log.info("Fetching leaves for student ID: {}", studentId);
        try {
            return leaveRepository.findByStudentIdContainingAndSchoolId(studentId, securityUtil.getSchoolId(), pageable);
        } catch (DataAccessException e) {
            log.error("Data access error fetching leaves for student ID: {}", studentId, e);
            throw new RuntimeException("Could not retrieve leaves by student ID due to data access issue", e);
        }
    }

    @Transactional(readOnly = true)
    public List<String> getLeavesByDateAndClass(String date, String className) {
        return getLeavesByDateAndClass(date, className, null);
    }

    /** @param sectionId when non-null, restricts to leaves whose student is currently in that
     *  section — see LeaveRepository#findByLeaveDateAndClassNameAndSchoolId. */
    @Transactional(readOnly = true)
    public List<String> getLeavesByDateAndClass(String date, String className, Long sectionId) {
        if (date == null || date.trim().isEmpty() || className == null || className.trim().isEmpty()) {
            log.warn("Attempted to get leaves by date/class with null/empty parameters. Date: {}, Class: {}", date, className);
            return Collections.emptyList();
        }
        log.info("Fetching leave student IDs for date: {} and class: {}", date, className);
        try {
            return leaveRepository.findByLeaveDateAndClassNameAndSchoolId(date, className, securityUtil.getSchoolId(), sectionId);
        } catch (DataAccessException e) {
            log.error("Data access error fetching leaves by date {} and class {}", date, className, e);
            throw new RuntimeException("Could not retrieve leaves due to data access issue", e);
        }
    }

    /**
     * Leave requests awaiting a decision, for the AI Copilot's read-only tools and its decision
     * workflow. Oldest first, capped.
     *
     * <p>{@code className} is the caller-scoping hook: the workflow layer passes a TEACHER's own
     * class here so the Copilot surface is class-confined, even though this module's existing
     * endpoints are only school-scoped. Passing null yields the school-wide view used by admins.
     */
    @Transactional(readOnly = true)
    public List<Leave> getLeavesForReview(String className, String studentId, int limit) {
        return getLeavesForReview(className, studentId, limit, null);
    }

    /** @param sectionId when non-null, restricts to leaves whose student is currently in that
     *  section — see LeaveRepository#findForReview. */
    @Transactional(readOnly = true)
    public List<Leave> getLeavesForReview(String className, String studentId, int limit, Long sectionId) {
        Long schoolId = securityUtil.getSchoolId();
        int capped = Math.max(1, Math.min(limit, 100));
        return leaveRepository.findForReview(
                LeaveStatus.PENDING, schoolId,
                (className != null && !className.isBlank()) ? className : null,
                (studentId != null && !studentId.isBlank()) ? studentId : null,
                sectionId,
                PageRequest.of(0, capped));
    }

    /**
     * Resolves specific leave ids within the caller's school, whatever their current status —
     * the workflow needs the real current status to report "already decided" honestly rather than
     * silently dropping the request. Ids outside the school simply do not come back.
     */
    @Transactional(readOnly = true)
    public List<Leave> getLeavesByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return leaveRepository.findByIdInAndSchoolId(ids, securityUtil.getSchoolId());
    }
}
