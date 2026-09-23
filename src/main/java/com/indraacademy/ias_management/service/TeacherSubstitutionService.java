package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.TeacherSubstitutionDtos.*;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TeacherSubstitutionService {
    private static final Logger log = LoggerFactory.getLogger(TeacherSubstitutionService.class);
    /** Reuses the existing TIMETABLE_EDIT permission — substitution assignment is, in effect,
     *  editing who teaches a timetable slot. SUB_ADMIN is NOT granted this by default (see
     *  PermissionSeeder), so an unmodified SUB_ADMIN can view uncovered periods/free teachers
     *  (covered by their default TIMETABLE_VIEW) but cannot assign/change/cancel substitutions
     *  unless a school admin has explicitly granted TIMETABLE_EDIT via the role-permission
     *  matrix — the smallest dedicated authorization, not a blanket SUB_ADMIN grant. */
    private static final String TIMETABLE_EDIT_PERMISSION = "TIMETABLE_EDIT";

    private final TeacherSubstitutionRepository substitutions;
    private final TimetableRepository timetables;
    private final TeacherRepository teachers;
    private final TeacherLeaveRepository leaves;
    private final TeacherAttendanceRepository attendance;
    private final TimetableSessionAccessService sessions;
    private final SecurityUtil security;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final PermissionService permissions;

    public TeacherSubstitutionService(TeacherSubstitutionRepository substitutions, TimetableRepository timetables,
            TeacherRepository teachers, TeacherLeaveRepository leaves, TeacherAttendanceRepository attendance,
            TimetableSessionAccessService sessions, SecurityUtil security, AuditService audit,
            ApplicationEventPublisher events, PermissionService permissions) {
        this.substitutions = substitutions;
        this.timetables = timetables;
        this.teachers = teachers;
        this.leaves = leaves;
        this.attendance = attendance;
        this.sessions = sessions;
        this.security = security;
        this.audit = audit;
        this.events = events;
        this.permissions = permissions;
    }

    /** Enforced only on the mutating operations (assign/change/cancel) — uncovered()/freeTeachers()
     *  remain read-only and stay behind the coarse role check alone, matching SUB_ADMIN's default
     *  TIMETABLE_VIEW grant. ADMIN is always allowed. SUB_ADMIN must hold TIMETABLE_EDIT explicitly;
     *  this fails CLOSED — if the permission lookup itself throws, the mutation is denied and the
     *  error is logged, rather than letting an under-permissioned SUB_ADMIN through on an outage. */
    private void requireTimetableEditPermission() {
        String role = security.getRole();
        if ("ADMIN".equals(role)) {
            return;
        }
        try {
            List<String> keys = permissions.getPermissionKeysForRole(role, security.getSchoolId());
            if (keys == null || !keys.contains(TIMETABLE_EDIT_PERMISSION)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Your role does not have permission to manage teacher substitutions.");
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Could not resolve permissions for role {}; denying substitution mutation: {}", role, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Your role does not have permission to manage teacher substitutions.");
        }
    }

    @Transactional(readOnly = true)
    public List<UncoveredPeriod> uncovered(LocalDate date) {
        requireDate(date);
        Long schoolId = security.getSchoolId();
        AcademicSession session = sessions.currentSessionOrNull(schoolId);
        if (session == null) return List.of();
        Day day = Day.valueOf(dayName(date.getDayOfWeek()));
        List<TimetableEntry> dayEntries = timetables.findByAcademicSessionIdAndSchoolId(session.getId(), schoolId)
                .stream().filter(e -> e.getDay() == day && e.getTeacherId() != null).toList();
        Set<String> unavailable = unavailableTeacherIds(schoolId, date);
        Map<Long, TeacherSubstitution> activeByEntry = substitutions
                .findBySchoolIdAndDateAndStatus(schoolId, date, TeacherSubstitutionStatus.ACTIVE).stream()
                .collect(Collectors.toMap(TeacherSubstitution::getTimetableEntryId, Function.identity()));
        List<Teacher> eligible = teachers.findByStatusAndSchoolId(TeacherStatus.ACTIVE, schoolId);
        List<TeacherSubstitution> active = new ArrayList<>(activeByEntry.values());
        return dayEntries.stream().filter(e -> unavailable.contains(e.getTeacherId()))
                .sorted(Comparator.comparing(TimetableEntry::getPeriodNumber).thenComparing(TimetableEntry::getClassName))
                .map(e -> toUncovered(e, activeByEntry.get(e.getId()), eligible, dayEntries, active, date)).toList();
    }

    @Transactional(readOnly = true)
    public List<FreeTeacher> freeTeachers(Long timetableEntryId, LocalDate date) {
        requireDate(date);
        Long schoolId = security.getSchoolId();
        TimetableEntry entry = timetables.findByIdAndSchoolId(timetableEntryId, schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Timetable entry not found."));
        requireEntryMatchesDate(entry, date);
        List<TimetableEntry> dayEntries = timetables.findByAcademicSessionIdAndSchoolId(entry.getAcademicSessionId(), schoolId)
                .stream().filter(e -> e.getDay() == entry.getDay()).toList();
        List<TeacherSubstitution> active = substitutions.findBySchoolIdAndDateAndStatus(
                schoolId, date, TeacherSubstitutionStatus.ACTIVE);
        return freeFor(entry, teachers.findByStatusAndSchoolId(TeacherStatus.ACTIVE, schoolId), dayEntries, active);
    }

    @Transactional
    public Assignment assign(UpsertRequest request, HttpServletRequest http) {
        requireTimetableEditPermission();
        requireDate(request.date());
        Long schoolId = security.getSchoolId();
        TimetableEntry initial = timetables.findByIdAndSchoolId(request.timetableEntryId(), schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Timetable entry not found."));
        sessions.lockOwnedSession(schoolId, initial.getAcademicSessionId());
        TimetableEntry entry = timetables.lockById(request.timetableEntryId(), schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Timetable entry not found."));
        requireCurrentSessionEntry(entry, schoolId);
        requireEntryMatchesDate(entry, request.date());
        if (!unavailableTeacherIds(schoolId, request.date()).contains(entry.getTeacherId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The original teacher is not unavailable on this date.");
        }
        if (substitutions.findBySchoolIdAndDateAndTimetableEntryIdAndStatus(schoolId, request.date(), entry.getId(),
                TeacherSubstitutionStatus.ACTIVE).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This period already has an active substitute.");
        }
        Teacher substitute = requireFreeSubstitute(entry, request.date(), request.substituteTeacherId(), null);
        TeacherSubstitution value = new TeacherSubstitution();
        value.setSchoolId(schoolId);
        value.setAcademicSessionId(entry.getAcademicSessionId());
        value.setDate(request.date());
        value.setTimetableEntryId(entry.getId());
        value.setOriginalTeacherId(entry.getTeacherId());
        value.setOriginalTeacherName(entry.getTeacherName() == null ? entry.getTeacherId() : entry.getTeacherName());
        value.setSubstituteTeacherId(substitute.getTeacherId());
        value.setSubstituteTeacherName(substitute.getName());
        value.setClassName(entry.getClassName());
        value.setSectionName(entry.getSectionName());
        value.setSubjectName(entry.getSubjectName());
        value.setPeriodNumber(entry.getPeriodNumber());
        value.setStartTime(entry.getStartTime());
        value.setEndTime(entry.getEndTime());
        value.setAssignedBy(security.getUsername());
        try {
            value = substitutions.saveAndFlush(value);
        } catch (DataIntegrityViolationException race) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This period was assigned by another administrator.", race);
        }
        audit.log(security.getUsername(), security.getRole(), "ASSIGN_TEACHER_SUBSTITUTION", "TeacherSubstitution",
                String.valueOf(value.getId()), null, summary(value), http.getRemoteAddr());
        publish(value, value.getSubstituteTeacherId(), "ASSIGNED");
        return Assignment.from(value);
    }

    @Transactional
    public Assignment change(Long id, ChangeRequest request, HttpServletRequest http) {
        requireTimetableEditPermission();
        Long schoolId = security.getSchoolId();
        TeacherSubstitution initial = substitutions.findById(id)
                .filter(s -> schoolId.equals(s.getSchoolId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Substitution not found."));
        sessions.lockOwnedSession(schoolId, initial.getAcademicSessionId());
        TeacherSubstitution value = substitutions.lockByIdAndSchoolId(id, schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Substitution not found."));
        if (value.getStatus() != TeacherSubstitutionStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A cancelled substitution cannot be changed.");
        }
        if (Objects.equals(value.getSubstituteTeacherId(), request.substituteTeacherId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This teacher is already assigned.");
        }
        TimetableEntry entry = timetables.findByIdAndSchoolId(value.getTimetableEntryId(), schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "The timetable period no longer exists."));
        Teacher replacement = requireFreeSubstitute(entry, value.getDate(), request.substituteTeacherId(), value.getId());
        String oldTeacher = value.getSubstituteTeacherId();
        String old = summary(value);
        value.setSubstituteTeacherId(replacement.getTeacherId());
        value.setSubstituteTeacherName(replacement.getName());
        value.setAssignedBy(security.getUsername());
        value = substitutions.saveAndFlush(value);
        audit.log(security.getUsername(), security.getRole(), "CHANGE_TEACHER_SUBSTITUTION", "TeacherSubstitution",
                String.valueOf(id), old, summary(value), http.getRemoteAddr());
        publish(value, oldTeacher, "CANCELLED");
        publish(value, value.getSubstituteTeacherId(), "ASSIGNED");
        return Assignment.from(value);
    }

    @Transactional
    public Assignment cancel(Long id, HttpServletRequest http) {
        requireTimetableEditPermission();
        Long schoolId = security.getSchoolId();
        TeacherSubstitution initial = substitutions.findById(id)
                .filter(s -> schoolId.equals(s.getSchoolId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Substitution not found."));
        sessions.lockOwnedSession(schoolId, initial.getAcademicSessionId());
        TeacherSubstitution value = substitutions.lockByIdAndSchoolId(id, schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Substitution not found."));
        if (value.getStatus() != TeacherSubstitutionStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Substitution is already cancelled.");
        }
        String old = summary(value);
        value.setStatus(TeacherSubstitutionStatus.CANCELLED);
        value.setAssignedBy(security.getUsername());
        value = substitutions.saveAndFlush(value);
        audit.log(security.getUsername(), security.getRole(), "CANCEL_TEACHER_SUBSTITUTION", "TeacherSubstitution",
                String.valueOf(id), old, summary(value), http.getRemoteAddr());
        publish(value, value.getSubstituteTeacherId(), "CANCELLED");
        return Assignment.from(value);
    }

    @Transactional(readOnly = true)
    public List<Assignment> mine(LocalDate date) {
        requireDate(date);
        return substitutions.findBySchoolIdAndSubstituteTeacherIdAndDateAndStatusOrderByPeriodNumberAsc(
                security.getSchoolId(), security.getUsername(), date, TeacherSubstitutionStatus.ACTIVE)
                .stream().map(Assignment::from).toList();
    }

    private Teacher requireFreeSubstitute(TimetableEntry entry, LocalDate date, String teacherId, Long ignoredAssignmentId) {
        Long schoolId = security.getSchoolId();
        if (Objects.equals(entry.getTeacherId(), teacherId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The original teacher cannot cover their own period.");
        }
        Teacher teacher = teachers.findByTeacherIdAndSchoolId(teacherId, schoolId)
                .filter(t -> t.getStatus() == TeacherStatus.ACTIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Substitute teacher is not active or eligible."));
        boolean scheduled = timetables.findByAcademicSessionIdAndTeacherIdAndSchoolId(
                entry.getAcademicSessionId(), teacherId, schoolId).stream()
                .anyMatch(e -> e.getDay() == entry.getDay() && Objects.equals(e.getPeriodNumber(), entry.getPeriodNumber()));
        boolean covering = substitutions.findBySchoolIdAndDateAndStatus(schoolId, date, TeacherSubstitutionStatus.ACTIVE)
                .stream().anyMatch(s -> !Objects.equals(s.getId(), ignoredAssignmentId)
                        && Objects.equals(s.getSubstituteTeacherId(), teacherId)
                        && Objects.equals(s.getPeriodNumber(), entry.getPeriodNumber()));
        if (scheduled || covering) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The selected teacher is already teaching during this period.");
        }
        return teacher;
    }

    private List<FreeTeacher> freeFor(TimetableEntry entry, List<Teacher> candidates,
            List<TimetableEntry> dayEntries, List<TeacherSubstitution> active) {
        Set<String> busy = dayEntries.stream()
                .filter(e -> Objects.equals(e.getPeriodNumber(), entry.getPeriodNumber()))
                .map(TimetableEntry::getTeacherId).filter(Objects::nonNull).collect(Collectors.toSet());
        active.stream().filter(s -> Objects.equals(s.getPeriodNumber(), entry.getPeriodNumber()))
                .map(TeacherSubstitution::getSubstituteTeacherId).forEach(busy::add);
        busy.add(entry.getTeacherId());
        return candidates.stream().filter(t -> !busy.contains(t.getTeacherId()))
                .sorted(Comparator.comparing(Teacher::getName, String.CASE_INSENSITIVE_ORDER))
                .map(t -> new FreeTeacher(t.getTeacherId(), t.getName())).toList();
    }

    private UncoveredPeriod toUncovered(TimetableEntry e, TeacherSubstitution assignment, List<Teacher> eligible,
            List<TimetableEntry> dayEntries, List<TeacherSubstitution> active, LocalDate date) {
        return new UncoveredPeriod(e.getId(), e.getTeacherId(),
                e.getTeacherName() == null ? e.getTeacherId() : e.getTeacherName(), e.getClassName(),
                e.getSectionName(), e.getSubjectName(), e.getPeriodNumber(), e.getStartTime(), e.getEndTime(),
                assignment == null ? null : Assignment.from(assignment), freeFor(e, eligible, dayEntries, active));
    }

    private Set<String> unavailableTeacherIds(Long schoolId, LocalDate date) {
        Set<String> ids = leaves.findApprovedOverlapping(schoolId, date, date).stream()
                .map(TeacherLeave::getTeacherId).collect(Collectors.toSet());
        attendance.findBySchoolIdAndDate(schoolId, date).stream()
                .filter(a -> "ABSENT".equals(a.getStatus()) || "ON_LEAVE".equals(a.getStatus()))
                .map(TeacherAttendance::getTeacherId).forEach(ids::add);
        return ids;
    }

    private void requireCurrentSessionEntry(TimetableEntry entry, Long schoolId) {
        AcademicSession current = sessions.currentSessionOrNull(schoolId);
        if (current == null || !Objects.equals(current.getId(), entry.getAcademicSessionId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Substitutions require a current-session timetable period.");
        }
    }
    private void requireEntryMatchesDate(TimetableEntry entry, LocalDate date) {
        if (!entry.getDay().name().equals(dayName(date.getDayOfWeek()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Timetable period does not occur on the selected date.");
        }
    }
    private void requireDate(LocalDate date) {
        if (date == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "date is required.");
    }
    private String dayName(DayOfWeek day) { return day.name(); }
    private String summary(TeacherSubstitution s) {
        return "date=" + s.getDate() + ",period=" + s.getPeriodNumber() + ",substitute="
                + s.getSubstituteTeacherId() + ",status=" + s.getStatus();
    }
    private void publish(TeacherSubstitution s, String recipient, String kind) {
        events.publishEvent(new TeacherSubstitutionNotificationEvent(s.getSchoolId(), s.getId(), s.getRevision(),
                recipient, kind, s.getClassName(), s.getSectionName(), s.getSubjectName(), s.getPeriodNumber(),
                s.getStartTime(), s.getEndTime(), s.getOriginalTeacherName(), s.getDate(), security.getUsername()));
    }
}
