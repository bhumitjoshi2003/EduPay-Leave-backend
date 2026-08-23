package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.TeacherLeaveApplyRequest;
import com.indraacademy.ias_management.dto.TeacherLeaveResponse;
import com.indraacademy.ias_management.entity.LeaveStatus;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.TeacherLeave;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.SchoolHoliday;
import com.indraacademy.ias_management.exception.InvalidLeaveStatusTransitionException;
import com.indraacademy.ias_management.repository.TeacherLeaveRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.SchoolHolidayRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import com.indraacademy.ias_management.util.SchoolTimeUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;

/**
 * A teacher's own leave application → admin approval, the missing counterpart to
 * TeacherAttendance's admin-only ON_LEAVE marking.
 *
 * <p>Kept as its own service rather than folded into (or sharing a generic base with)
 * {@link LeaveService} — the same reasoning TeacherAttendanceService already documents for
 * staying separate from student AttendanceService: a parallel, independently-evolvable service
 * per domain, not a shared/polymorphic one. It reuses the actual reusable PIECES from the student
 * Leave hardening instead — {@link LeaveStatus} (no student-specific meaning to duplicate) and
 * {@link InvalidLeaveStatusTransitionException} (already fully generic) — without touching
 * LeaveService's own code, so nothing here can regress it.
 *
 * <p>The same same-status-repeat guard applies here as there: re-approving an already-approved
 * leave, or re-rejecting an already-rejected one, is refused as a no-op rather than silently
 * reapplied — see that exception's Javadoc for why (duplicate audit entries, duplicate
 * notifications). Reversal (APPROVED ↔ REJECTED) remains possible, matching the same product
 * decision made for student leave.
 */
@Service
public class TeacherLeaveService {

    private static final Logger log = LoggerFactory.getLogger(TeacherLeaveService.class);

    @Autowired private TeacherLeaveRepository teacherLeaveRepository;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private SchoolRepository schoolRepository;
    @Autowired private SchoolHolidayRepository schoolHolidayRepository;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private AuditService auditService;
    @Autowired private NotificationService notificationService;
    @Autowired private TeacherAttendanceScheduleService teacherAttendanceScheduleService;
    @Autowired private Clock clock;

    @Transactional
    public TeacherLeaveResponse applyLeave(TeacherLeaveApplyRequest req, HttpServletRequest request) {
        if (req.getStartDate() == null || req.getEndDate() == null) {
            throw new IllegalArgumentException("Start date and end date are required.");
        }
        if (req.getEndDate().isBefore(req.getStartDate())) {
            throw new IllegalArgumentException("End date cannot be before start date.");
        }
        if (req.getReason() == null || req.getReason().isBlank()) {
            throw new IllegalArgumentException("A reason is required.");
        }

        Long schoolId = securityUtil.getSchoolId();
        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new NoSuchElementException("School not found: " + schoolId));
        LocalDate today = LocalDate.now(clock.withZone(SchoolTimeUtil.zoneId(school)));
        if (req.getStartDate().isBefore(today)) {
            throw new IllegalArgumentException("Leave cannot be applied for a past date.");
        }
        List<SchoolHoliday> holidays = schoolHolidayRepository.findOverlapping(
                schoolId, req.getStartDate(), req.getEndDate());
        String teacherId = securityUtil.getUsername();
        long workingLeaveDays = countWorkingLeaveDays(teacherId, schoolId,
                req.getStartDate(), req.getEndDate(), school.getWorkingDays(), holidays);
        if (workingLeaveDays == 0) {
            throw new IllegalArgumentException(
                    "The selected dates contain no working days. Leave cannot be applied on closed days or school holidays.");
        }
        // teacherId is NEVER taken from the request body — always the authenticated caller,
        // the same principle applyLeave (student) already enforces for studentId.
        Teacher teacher = teacherRepository.findByTeacherIdAndSchoolId(teacherId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Teacher not found: " + teacherId));

        TeacherLeave leave = new TeacherLeave();
        leave.setSchoolId(schoolId);
        leave.setTeacherId(teacherId);
        leave.setTeacherName(teacher.getName());
        leave.setStartDate(req.getStartDate());
        leave.setEndDate(req.getEndDate());
        leave.setReason(req.getReason());
        leave.setStatus(LeaveStatus.PENDING);

        TeacherLeave saved = teacherLeaveRepository.save(leave);
        log.info("Teacher {} applied for leave {} to {} (school {})", teacherId, saved.getStartDate(), saved.getEndDate(), schoolId);

        auditService.log(
                teacherId,
                securityUtil.getRole(),
                "APPLY_TEACHER_LEAVE",
                "TeacherLeave",
                String.valueOf(saved.getId()),
                null,
                "startDate=" + saved.getStartDate() + ",endDate=" + saved.getEndDate() + ",status=PENDING",
                request.getRemoteAddr()
        );

        notificationService.createAutoGeneratedIndividualNotification(
                "Leave Applied",
                String.format("Your leave application for %s to %s has been submitted.", saved.getStartDate(), saved.getEndDate()),
                "Teacher_Leave_Applied",
                teacherId,
                "TeacherLeave",
                String.valueOf(saved.getId())
        );

        return TeacherLeaveResponse.from(saved, workingLeaveDays);
    }

    @Transactional
    public TeacherLeaveResponse updateStatus(Long leaveId, LeaveStatus status, HttpServletRequest request) {
        Long schoolId = securityUtil.getSchoolId();
        String adminUser = securityUtil.getUsername();

        // Locked read — same reasoning as LeaveRepository.findByIdForUpdate: two admins deciding
        // the same request at nearly the same moment serialize here instead of racing.
        TeacherLeave leave = teacherLeaveRepository.findByIdForUpdate(leaveId)
                .orElseThrow(() -> new NoSuchElementException("Leave request not found: " + leaveId));

        if (!schoolId.equals(leave.getSchoolId())) {
            throw new SecurityException("Access denied: leave request does not belong to your school.");
        }
        if (status == leave.getStatus()) {
            throw new InvalidLeaveStatusTransitionException(leaveId, leave.getStatus(), status);
        }

        LeaveStatus oldStatus = leave.getStatus();
        leave.setStatus(status);
        TeacherLeave saved = teacherLeaveRepository.save(leave);
        log.info("Admin {} set teacher leave {} to {} (school {})", adminUser, leaveId, status, schoolId);

        auditService.log(
                adminUser,
                securityUtil.getRole(),
                "UPDATE_TEACHER_LEAVE_STATUS",
                "TeacherLeave",
                String.valueOf(leaveId),
                "status=" + oldStatus,
                "status=" + status,
                request.getRemoteAddr()
        );

        notificationService.createAutoGeneratedIndividualNotification(
                "Leave Status Updated",
                String.format("Your leave application for %s to %s has been %s.",
                        saved.getStartDate(), saved.getEndDate(), status.name().toLowerCase()),
                "Teacher_Leave_Status_" + status.name(),
                saved.getTeacherId(),
                "TeacherLeave",
                String.valueOf(saved.getId())
        );

        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new NoSuchElementException("School not found: " + schoolId));
        return toCalendarAwareResponse(saved, school);
    }

    /**
     * A teacher may cancel only their OWN request, and only while it's still PENDING — once
     * decided, correcting it goes through an admin, so the decision and its audit trail stay
     * intact. An admin may cancel any request regardless of status, mirroring
     * LeaveService.deleteLeaveById's identical admin-unrestricted precedent.
     */
    @Transactional
    public void cancelLeave(Long leaveId, HttpServletRequest request) {
        Long schoolId = securityUtil.getSchoolId();
        String userId = securityUtil.getUsername();
        String role = securityUtil.getRole();

        // Locked read — same reasoning as updateStatus: without it, a teacher's cancel and an
        // admin's simultaneous decision on the same request could race, letting the cancel
        // delete a leave that was just approved/rejected with no re-validation.
        TeacherLeave leave = teacherLeaveRepository.findByIdForUpdate(leaveId)
                .orElseThrow(() -> new NoSuchElementException("Leave request not found: " + leaveId));
        if (!schoolId.equals(leave.getSchoolId())) {
            throw new SecurityException("Access denied: leave request does not belong to your school.");
        }

        if (Role.TEACHER.equals(role)) {
            if (!Objects.equals(userId, leave.getTeacherId())) {
                throw new SecurityException("Access denied: this is not your leave request.");
            }
            if (leave.getStatus() != LeaveStatus.PENDING) {
                throw new IllegalStateException(
                        "Only a pending leave request can be cancelled. Contact an admin to change a decided request.");
            }
        }

        String oldValue = "startDate=" + leave.getStartDate() + ",endDate=" + leave.getEndDate() + ",status=" + leave.getStatus();
        teacherLeaveRepository.deleteById(leaveId);
        log.info("{} ({}) cancelled teacher leave {} (school {})", userId, role, leaveId, schoolId);

        auditService.log(
                userId,
                role,
                "CANCEL_TEACHER_LEAVE",
                "TeacherLeave",
                String.valueOf(leaveId),
                oldValue,
                null,
                request.getRemoteAddr()
        );
    }

    @Transactional(readOnly = true)
    public Page<TeacherLeaveResponse> getMyLeaves(Pageable pageable) {
        Long schoolId = securityUtil.getSchoolId();
        String teacherId = securityUtil.getUsername();
        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new NoSuchElementException("School not found: " + schoolId));
        return teacherLeaveRepository.findByTeacherIdAndSchoolIdOrderByStartDateDesc(teacherId, schoolId, pageable)
                .map(leave -> toCalendarAwareResponse(leave, school));
    }

    @Transactional(readOnly = true)
    public Page<TeacherLeaveResponse> getLeavesFiltered(LeaveStatus status, String teacherId, LocalDate date, Pageable pageable) {
        Long schoolId = securityUtil.getSchoolId();
        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new NoSuchElementException("School not found: " + schoolId));
        return teacherLeaveRepository.findFiltered(schoolId, status, teacherId, date, pageable)
                .map(leave -> toCalendarAwareResponse(leave, school));
    }

    @Transactional(readOnly = true)
    public Map<String, String> getCalendarConfig() {
        School school = schoolRepository.findById(securityUtil.getSchoolId())
                .orElseThrow(() -> new NoSuchElementException("School not found"));
        Set<String> configuredDays = parseConfiguredWorkingDays(school.getWorkingDays());
        return Map.of("workingDays", String.join(",", configuredDays),
                "timezone", SchoolTimeUtil.zoneId(school).getId());
    }

    private TeacherLeaveResponse toCalendarAwareResponse(TeacherLeave leave, School school) {
        List<SchoolHoliday> holidays = schoolHolidayRepository.findOverlapping(
                leave.getSchoolId(), leave.getStartDate(), leave.getEndDate());
        return TeacherLeaveResponse.from(leave, countWorkingLeaveDays(
                leave.getTeacherId(), leave.getSchoolId(), leave.getStartDate(), leave.getEndDate(),
                school.getWorkingDays(), holidays));
    }

    private long countWorkingLeaveDays(String teacherId, Long schoolId, LocalDate start, LocalDate end,
                                       String schoolWorkingDays, List<SchoolHoliday> holidays) {
        Map<String, List<com.indraacademy.ias_management.entity.TeacherAttendanceSchedule>> schedules =
                teacherAttendanceScheduleService.schedulesByTeacher(schoolId, start, end);
        return start.datesUntil(end.plusDays(1))
                .filter(date -> parseConfiguredWorkingDays(teacherAttendanceScheduleService.workingDaysFor(
                        teacherId, date, schoolWorkingDays, schedules)).contains(date.getDayOfWeek().name()))
                .filter(date -> holidays.stream().noneMatch(holiday ->
                        !date.isBefore(holiday.getStartDate()) && !date.isAfter(holiday.getEndDate())))
                .count();
    }

    private Set<String> parseConfiguredWorkingDays(String workingDays) {
        if (workingDays == null || workingDays.isBlank()) {
            throw new IllegalStateException("School working days are not configured. Please update School Settings.");
        }
        Set<String> configuredDays = Arrays.stream(workingDays.split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .filter(day -> !day.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (configuredDays.isEmpty()) {
            throw new IllegalStateException("School working days are not configured. Please update School Settings.");
        }
        return configuredDays;
    }
}
