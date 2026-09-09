package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.entity.TimetableCorrectionRequest.Status;
import com.indraacademy.ias_management.notification.*;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
public class TimetableCorrectionService {
    @Autowired private TimetableCorrectionRequestRepository requests;
    @Autowired private TimetableRepository entries;
    @Autowired private TeacherRepository teachers;
    @Autowired private TimetableService timetable;
    @Autowired private TimetableValidationService validation;
    @Autowired private TimetableSessionAccessService sessions;
    @Autowired private SecurityUtil security;
    @Autowired private BusinessNotificationService notifications;
    @Autowired private AuditService audit;
    @Autowired private Clock clock;

    public record View(Long id, Long timetableEntryId, Long academicSessionId, Status status, String reason,
                       LocalDateTime createdAt, LocalDateTime reviewedAt, String requestedTeacherName,
                       String expectedTeacherName, TimetableEntry entry) {}

    @Transactional(readOnly = true)
    public List<View> list(String teacherId) {
        Long school = security.getSchoolId();
        return (teacherId == null ? requests.findBySchoolIdAndStatusOrderByCreatedAtDesc(school, Status.PENDING)
                : requests.findBySchoolIdAndRequestedByTeacherIdOrderByCreatedAtDesc(school, teacherId))
                .stream().map(this::view).toList();
    }

    @Transactional
    public View submit(Long entryId, String teacherId, String requestedTeacherId, String reason, HttpServletRequest http) {
        Long school = security.getSchoolId();
        timetable.requireActiveTeacher(teacherId, school);
        if (requestedTeacherId != null && !teacherId.equals(requestedTeacherId))
            throw new SecurityException("You may only request this assignment for yourself.");
        sessions.lockOwnedSession(school, sessions.requireCurrentSessionForTeacherWrite(school).getId());
        TimetableEntry entry = entries.lockById(entryId, school)
                .orElseThrow(() -> new NoSuchElementException("Timetable entry not found."));
        eligible(entry, teacherId, school);
        if (entry.getTeacherId() == null || entry.getTeacherId().isBlank() || teacherId.equals(entry.getTeacherId()))
            throw new TimetableCorrectionConflict("This entry is not assigned to another teacher.");
        if (requests.existsBySchoolIdAndTimetableEntryIdAndRequestedByTeacherIdAndStatus(school, entryId, teacherId, Status.PENDING))
            throw new TimetableCorrectionConflict("You already have a pending request for this entry.");
        TimetableCorrectionRequest r = new TimetableCorrectionRequest();
        r.setSchoolId(school); r.setAcademicSessionId(entry.getAcademicSessionId()); r.setTimetableEntryId(entryId);
        r.setRequestedByTeacherId(teacherId); r.setRequestedTeacherId(teacherId);
        r.setExpectedCurrentTeacherId(entry.getTeacherId()); r.setExpectedRevision(entry.getRevision());
        r.setStatus(Status.PENDING); r.setReason(reason); r.setCreatedAt(LocalDateTime.now(clock));
        requests.saveAndFlush(r);
        audit.log(security.getUsername(), security.getRole(), "REQUEST_TIMETABLE_CORRECTION", "TimetableCorrectionRequest",
                r.getId().toString(), null, "PENDING", http.getRemoteAddr());
        notify(r, NotificationEventCode.TIMETABLE_CORRECTION_SUBMITTED,
                new NotificationAudience(NotificationAudienceType.ROLE, "ADMIN"), "Timetable correction requested",
                "A teacher has requested a correction to a timetable assignment.", "submitted");
        return view(r);
    }

    @Transactional
    public View review(Long id, boolean approve, HttpServletRequest http) {
        Long school = security.getSchoolId();
        TimetableCorrectionRequest r = requests.lockById(id, school)
                .orElseThrow(() -> new NoSuchElementException("Correction request not found."));
        if (r.getStatus() != Status.PENDING) throw new TimetableCorrectionConflict("This request has already been reviewed.");
        if (approve) {
            sessions.lockOwnedSession(school, r.getAcademicSessionId());
            TimetableEntry entry = r.getTimetableEntryId() == null ? null
                    : entries.lockById(r.getTimetableEntryId(), school).orElse(null);
            if (entry == null || !Objects.equals(entry.getAcademicSessionId(), r.getAcademicSessionId())
                    || !Objects.equals(entry.getTeacherId(), r.getExpectedCurrentTeacherId())
                    || entry.getRevision() != r.getExpectedRevision())
                throw new TimetableCorrectionConflict("The timetable entry changed or was deleted. Submit a new correction request.");
            eligible(entry, r.getRequestedTeacherId(), school);
            Teacher replacement = timetable.requireActiveTeacher(r.getRequestedTeacherId(), school);
            String oldTeacher = entry.getTeacherId();
            entry.setTeacherId(replacement.getTeacherId()); entry.setTeacherName(replacement.getName());
            validation.validateSubjectOwnership(entry, school, entry.getAcademicSessionId(), entry.getId());
            validation.validate(entry, school, entry.getAcademicSessionId(), entry.getId());
            entries.saveAndFlush(entry);
            audit.logUpdate(security.getUsername(), security.getRole(), "CORRECT_TIMETABLE_ENTRY", "TimetableEntry",
                    entry.getId().toString(), oldTeacher, entry.getTeacherId(), http.getRemoteAddr());
        }
        r.setStatus(approve ? Status.APPROVED : Status.REJECTED);
        r.setReviewedAt(LocalDateTime.now(clock)); r.setReviewedBy(security.getUsername());
        requests.saveAndFlush(r);
        audit.log(security.getUsername(), security.getRole(), "REVIEW_TIMETABLE_CORRECTION", "TimetableCorrectionRequest",
                r.getId().toString(), "PENDING", r.getStatus().name(), http.getRemoteAddr());
        notify(r, approve ? NotificationEventCode.TIMETABLE_CORRECTION_APPROVED : NotificationEventCode.TIMETABLE_CORRECTION_REJECTED,
                NotificationAudience.directUser(r.getRequestedTeacherId()), "Timetable correction " + r.getStatus().name().toLowerCase(),
                "Your timetable correction request has been " + r.getStatus().name().toLowerCase() + ".", "reviewed");
        return view(r);
    }

    private void eligible(TimetableEntry entry, String teacher, Long school) {
        timetable.requireCurrentEntry(entry, school);
        timetable.authorizeTeacherWrite(teacher, school, entry.getAcademicSessionId(), entry.getClassId(), entry.getClassName(), entry.getSectionId());
    }
    private void notify(TimetableCorrectionRequest r, NotificationEventCode event, NotificationAudience audience,
                        String title, String message, String suffix) {
        notifications.publish(r.getSchoolId(), event, NotificationCategory.ACADEMICS_RESULTS, title, message, audience,
                "TimetableCorrectionRequest", r.getId().toString(), "/dashboard/timetable", security.getUsername(),
                "timetable-correction:" + r.getId() + ":" + suffix, Set.of(ExternalDeliveryChannel.PUSH));
    }
    private String name(String id, Long school) {
        return teachers.findByTeacherIdAndSchoolId(id, school).map(Teacher::getName).orElse("Teacher unavailable");
    }
    private View view(TimetableCorrectionRequest r) {
        TimetableEntry entry = r.getTimetableEntryId() == null ? null
                : entries.findByIdAndSchoolId(r.getTimetableEntryId(), r.getSchoolId()).orElse(null);
        return new View(r.getId(), r.getTimetableEntryId(), r.getAcademicSessionId(), r.getStatus(), r.getReason(),
                r.getCreatedAt(), r.getReviewedAt(), name(r.getRequestedTeacherId(), r.getSchoolId()),
                name(r.getExpectedCurrentTeacherId(), r.getSchoolId()), entry);
    }
}
