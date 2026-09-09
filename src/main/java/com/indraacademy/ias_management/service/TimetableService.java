package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.TimetableDtos.TimetableEntryRequest;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.entity.Section;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.TimetableEntry;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SectionRepository;
import com.indraacademy.ias_management.repository.TeacherClassGrantRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.repository.TimetableRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Phase F3: timetable configuration is now AcademicSession-scoped. Every write (create, update,
 * delete) requires an explicit, tenant-verified {@code academicSessionId} for ADMIN/SUPER_ADMIN
 * callers; a TEACHER caller's self-service writes always target the server-resolved CURRENT
 * session instead — a teacher can never choose an arbitrary historical or future session (see
 * {@link TimetableSessionAccessService}). Reads default to the current session (operational reads
 * for teacher/student/admin screens) unless an explicit session id is supplied for an admin
 * historical/future view.
 *
 * <p>The timetable records assignments; it does not adjudicate whether multiple assignments in the
 * same school/session/class/section/day/period are logically correct (same subject, same teacher,
 * or otherwise) — any number of rows may coexist in a slot. Reviewing and correcting mistakes is
 * the ADMIN's responsibility. This service still enforces tenant isolation, active-teacher status,
 * ownership (a TEACHER may only create/update/delete their own rows), valid class/section, and
 * current-session-only teacher writes.
 *
 * <p>Legacy rows with {@code academicSessionId = null} are untouched by this phase: no method
 * here ever creates one, and no session-scoped query can ever match one (SQL equality against a
 * non-null bound parameter never returns a NULL row) — see the Phase F3 report for the resulting
 * operational consequence.
 */
@Service
public class TimetableService {

    private static final Logger log = LoggerFactory.getLogger(TimetableService.class);

    @Autowired private TimetableRepository timetableRepository;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private SchoolClassRepository schoolClassRepository;
    @Autowired private TeacherClassScopeService teacherClassScopeService;
    @Autowired private TeacherClassGrantRepository teacherClassGrantRepository;
    @Autowired private TimetableSessionAccessService sessionAccess;
    @Autowired private AuditService auditService;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private ObjectMapper objectMapper;

    // ── Operational reads: resolve current session server-side, never fall back to legacy NULL rows ──

    @Transactional(readOnly = true)
    public List<TimetableEntry> getByClass(String className, Long sectionId) {
        Long schoolId = securityUtil.getSchoolId();
        AcademicSession current = sessionAccess.currentSessionOrNull(schoolId);
        if (current == null) return List.of();
        return readByClass(schoolId, current.getId(), className, sectionId);
    }

    @Transactional(readOnly = true)
    public List<TimetableEntry> getByTeacher(String teacherId) {
        Long schoolId = securityUtil.getSchoolId();
        AcademicSession current = sessionAccess.currentSessionOrNull(schoolId);
        if (current == null) return List.of();
        return timetableRepository.findByAcademicSessionIdAndTeacherIdAndSchoolIdOrderByDayAscPeriodNumberAsc(
                current.getId(), teacherId, schoolId);
    }

    // ── Admin explicit reads: any session, including historical/future — configuration visibility
    //    only, never an authorization source. Role check happens at the controller. ──────────────

    @Transactional(readOnly = true)
    public List<TimetableEntry> getByClassForSession(String className, Long sectionId, Long academicSessionId) {
        Long schoolId = securityUtil.getSchoolId();
        sessionAccess.requireOwnedSession(schoolId, academicSessionId);
        return readByClass(schoolId, academicSessionId, className, sectionId);
    }

    @Transactional(readOnly = true)
    public List<TimetableEntry> getByTeacherForSession(String teacherId, Long academicSessionId) {
        Long schoolId = securityUtil.getSchoolId();
        sessionAccess.requireOwnedSession(schoolId, academicSessionId);
        return timetableRepository.findByAcademicSessionIdAndTeacherIdAndSchoolIdOrderByDayAscPeriodNumberAsc(
                academicSessionId, teacherId, schoolId);
    }

    private List<TimetableEntry> readByClass(Long schoolId, Long academicSessionId, String className, Long sectionId) {
        if (sectionId != null) {
            return timetableRepository.findByAcademicSessionIdAndClassNameAndSectionIdAndSchoolIdOrderByDayAscPeriodNumberAsc(
                    academicSessionId, className, sectionId, schoolId);
        }
        return timetableRepository.findByAcademicSessionIdAndClassNameAndSchoolIdOrderByDayAscPeriodNumberAsc(
                academicSessionId, className, schoolId);
    }

    // ── Writes ───────────────────────────────────────────────────────────────────────────────

    /**
     * @param role             ADMIN/SUPER_ADMIN may create a period for any teacher in any
     *                         (non-historical) session they own; TEACHER may only add themselves
     *                         into a class/section they already have a real relationship with in
     *                         the CURRENT session (see {@link #authorizeTeacherWrite}), and their
     *                         teacherId is always forced to their own id regardless of what the
     *                         request carries.
     * @param currentTeacherId the caller's own teacherId; ignored unless role is TEACHER.
     */
    @Transactional
    public TimetableEntry create(TimetableEntryRequest req, String role, String currentTeacherId, HttpServletRequest request) {
        Long schoolId = securityUtil.getSchoolId();
        AcademicSession targetSession = Role.TEACHER.equals(role)
                ? sessionAccess.requireCurrentSessionForTeacherWrite(schoolId)
                : sessionAccess.requireWritableOwnedSession(schoolId, req.academicSessionId());

        sessionAccess.lockOwnedSession(schoolId, targetSession.getId());
        SchoolClass schoolClass = requireOwnedClass(schoolId, req.classId());
        Section section = resolveAndRequireSection(schoolId, schoolClass, req.sectionId());

        TimetableEntry entry = new TimetableEntry();
        entry.setSchoolId(schoolId);
        entry.setAcademicSessionId(targetSession.getId());
        entry.setClassId(schoolClass.getId());
        entry.setClassName(schoolClass.getName());
        entry.setSectionId(section != null ? section.getId() : null);
        entry.setSectionName(section != null ? section.getName() : null);
        entry.setDay(req.day());
        entry.setPeriodNumber(req.periodNumber());
        entry.setStartTime(req.startTime());
        entry.setEndTime(req.endTime());
        entry.setSubjectName(req.subjectName());
        entry.setTeacherId(req.teacherId());

        if (Role.TEACHER.equals(role)) {
            entry.setTeacherId(currentTeacherId);
            authorizeTeacherWrite(currentTeacherId, schoolId, targetSession.getId(),
                    schoolClass.getId(), schoolClass.getName(), entry.getSectionId());
        }

        resolveTeacherName(entry);

        TimetableEntry saved = timetableRepository.save(entry);
        log.info("Timetable entry created: id={}, sessionId={}, classId={}, day={}, period={}",
                saved.getId(), saved.getAcademicSessionId(), saved.getClassId(), saved.getDay(), saved.getPeriodNumber());

        auditService.log(
                securityUtil.getUsername(), securityUtil.getRole(), "CREATE_TIMETABLE_ENTRY",
                "TimetableEntry", saved.getId().toString(), null, toJson(saved), request.getRemoteAddr());

        return saved;
    }

    /** Admin configuration and current-session teacher-owned updates. */
    @Transactional
    public TimetableEntry update(Long id, TimetableEntryRequest req, HttpServletRequest request) {
        return update(id, req, securityUtil.getRole(), securityUtil.getUsername(), request);
    }

    @Transactional
    public TimetableEntry update(Long id, TimetableEntryRequest req, String role, String teacherId, HttpServletRequest request) {
        Long schoolId = securityUtil.getSchoolId();
        TimetableEntry existing = timetableRepository.findById(id)
                .filter(e -> schoolId.equals(e.getSchoolId()))
                .orElseThrow(() -> new NoSuchElementException("Timetable entry not found: " + id));

        if (Role.TEACHER.equals(role)) authorizeOwnedMutation(existing, teacherId, schoolId);

        // Fail closed: the requested target session must match the row's actual session — an
        // update can never move a row from one session to another, and never silently targets
        // whichever session happens to be current.
        if (!Objects.equals(existing.getAcademicSessionId(), req.academicSessionId())) {
            throw new DataIntegrityViolationException(
                    "The requested session does not match this entry's actual session. "
                            + "This entry belongs to session " + existing.getAcademicSessionId() + ".");
        }
        sessionAccess.requireWritableOwnedSession(schoolId, existing.getAcademicSessionId());
        sessionAccess.lockOwnedSession(schoolId, existing.getAcademicSessionId());

        SchoolClass schoolClass = requireOwnedClass(schoolId, req.classId());
        Section section = resolveAndRequireSection(schoolId, schoolClass, req.sectionId());

        // Authorize the target before mutating the managed entity: otherwise a query flush
        // could let the moved row itself manufacture a new teaching relationship.
        if (Role.TEACHER.equals(role)) authorizeTeacherWrite(teacherId, schoolId, existing.getAcademicSessionId(),
                schoolClass.getId(), schoolClass.getName(), section == null ? null : section.getId());
        String oldValue = toJson(existing);

        existing.setClassId(schoolClass.getId());
        existing.setClassName(schoolClass.getName());
        existing.setSectionId(section != null ? section.getId() : null);
        existing.setSectionName(section != null ? section.getName() : null);
        existing.setDay(req.day());
        existing.setPeriodNumber(req.periodNumber());
        existing.setStartTime(req.startTime());
        existing.setEndTime(req.endTime());
        existing.setSubjectName(req.subjectName());
        existing.setTeacherId(Role.TEACHER.equals(role) ? teacherId : req.teacherId());
        if (Role.TEACHER.equals(role)) {
            authorizeTeacherWrite(teacherId, schoolId, existing.getAcademicSessionId(),
                    existing.getClassId(), existing.getClassName(), existing.getSectionId());
        }

        resolveTeacherName(existing);

        TimetableEntry saved = timetableRepository.save(existing);
        log.info("Timetable entry updated: id={}, sessionId={}", saved.getId(), saved.getAcademicSessionId());

        auditService.logUpdate(
                securityUtil.getUsername(), securityUtil.getRole(), "UPDATE_TIMETABLE_ENTRY",
                "TimetableEntry", saved.getId().toString(), oldValue, toJson(saved), request.getRemoteAddr());

        return saved;
    }

    /** Admin configuration and current-session teacher-owned deletion. */
    @Transactional
    public void delete(Long id, Long requestedAcademicSessionId, HttpServletRequest request) {
        delete(id, requestedAcademicSessionId, securityUtil.getRole(), securityUtil.getUsername(), request);
    }

    @Transactional
    public void delete(Long id, Long requestedAcademicSessionId, String role, String teacherId, HttpServletRequest request) {
        Long schoolId = securityUtil.getSchoolId();
        TimetableEntry existing = timetableRepository.findById(id)
                .filter(e -> schoolId.equals(e.getSchoolId()))
                .orElseThrow(() -> new NoSuchElementException("Timetable entry not found: " + id));

        if (Role.TEACHER.equals(role)) authorizeOwnedMutation(existing, teacherId, schoolId);

        if (!Objects.equals(existing.getAcademicSessionId(), requestedAcademicSessionId)) {
            throw new DataIntegrityViolationException(
                    "The requested session does not match this entry's actual session. "
                            + "This entry belongs to session " + existing.getAcademicSessionId() + ".");
        }
        sessionAccess.requireWritableOwnedSession(schoolId, existing.getAcademicSessionId());
        sessionAccess.lockOwnedSession(schoolId, existing.getAcademicSessionId());

        String oldValue = toJson(existing);
        timetableRepository.deleteById(id);
        log.info("Timetable entry deleted: id={}, sessionId={}", id, existing.getAcademicSessionId());

        auditService.log(
                securityUtil.getUsername(), securityUtil.getRole(), "DELETE_TIMETABLE_ENTRY",
                "TimetableEntry", id.toString(), oldValue, null, request.getRemoteAddr());
    }

    // ── Canonical class/section resolution — never trust a client-provided name ─────────────

    private SchoolClass requireOwnedClass(Long schoolId, Long classId) {
        return schoolClassRepository.findByIdAndSchoolId(classId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Class not found: " + classId));
    }

    /** Null sectionId is required for a class with no configured sections, and a valid,
     *  same-class sectionId is required for a class that has any — the same "explicit valid
     *  section where the product already requires one" rule TeacherClassScopeService enforces
     *  elsewhere. Never falls back to guessing a section from a name. */
    private Section resolveAndRequireSection(Long schoolId, SchoolClass schoolClass, Long sectionId) {
        boolean hasSections = !sectionRepository
                .findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(schoolId, schoolClass.getId(), true)
                .isEmpty();
        if (!hasSections) {
            if (sectionId != null) {
                throw new IllegalArgumentException(
                        "Class " + schoolClass.getName() + " has no sections; sectionId must not be supplied.");
            }
            return null;
        }
        if (sectionId == null) {
            throw new IllegalArgumentException(
                    "Class " + schoolClass.getName() + " has sections; a sectionId is required.");
        }
        Section section = sectionRepository.findByIdAndSchoolId(sectionId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Section not found: " + sectionId));
        if (!Objects.equals(section.getClassId(), schoolClass.getId())) {
            throw new IllegalArgumentException("Section " + sectionId + " does not belong to class " + schoolClass.getName() + ".");
        }
        return section;
    }

    // ── TEACHER self-service authorization (live grant/relationship semantics — unchanged) ───

    /**
     * TEACHER-only guard for timetable mutations: a teacher may
     * only add a period into a class+section they already have a real relationship with — they
     * already teach there THIS SESSION (an existing timetable entry in the current session names
     * them), they're its class-teacher (live {@code Teacher.classTeacher}/
     * {@code classTeacherSectionId} — never a historical/future responsibility record), or an
     * admin has explicitly granted them access (see TeacherClassGrantService — current, not
     * session-scoped, unchanged by this phase). ADMIN/SUPER_ADMIN never call this.
     */
    void authorizeTeacherWrite(String teacherId, Long schoolId, Long academicSessionId,
            Long classId, String className, Long sectionId) {
        requireActiveTeacher(teacherId, schoolId);
        boolean alreadyTeachesHereThisSession = timetableRepository
                .findByAcademicSessionIdAndTeacherIdAndSchoolId(academicSessionId, teacherId, schoolId).stream()
                .anyMatch(e -> Objects.equals(e.getClassId(), classId) && Objects.equals(e.getSectionId(), sectionId));
        if (alreadyTeachesHereThisSession) return;

        TeacherClassScopeService.TeacherScope scope = teacherClassScopeService.resolveOwnScope(teacherId, schoolId);
        boolean isOwnClassTeacherSlot = scope.hasClassResponsibility()
                && !scope.sectionRequiredButMissing()
                && Objects.equals(scope.className(), className)
                && Objects.equals(scope.sectionId(), sectionId);
        if (isOwnClassTeacherSlot) return;

        boolean hasAdminGrant = teacherClassGrantRepository
                .existsByTeacherIdAndClassNameAndSectionIdAndSchoolId(teacherId, className, sectionId, schoolId);
        if (hasAdminGrant) return;

        throw new SecurityException(
                "You can only add periods for a class or section you already teach, are the class-teacher of, "
                        + "or have been granted access to. Ask an admin to grant you access to this class.");
    }

    Teacher requireActiveTeacher(String teacherId, Long schoolId) {
        return teacherRepository.findByTeacherIdAndSchoolId(teacherId, schoolId)
                .filter(t -> t.getStatus() == com.indraacademy.ias_management.entity.TeacherStatus.ACTIVE)
                .orElseThrow(() -> new SecurityException("Only an active teacher may change the timetable."));
    }

    void requireCurrentEntry(TimetableEntry entry, Long schoolId) {
        AcademicSession current = sessionAccess.requireCurrentSessionForTeacherWrite(schoolId);
        if (!Objects.equals(current.getId(), entry.getAcademicSessionId()))
            throw new SecurityException("Teachers may only change the current session's timetable.");
    }

    private void authorizeOwnedMutation(TimetableEntry entry, String teacherId, Long schoolId) {
        requireActiveTeacher(teacherId, schoolId);
        if (!Objects.equals(entry.getTeacherId(), teacherId))
            throw new SecurityException("You may only change your own timetable entries.");
        requireCurrentEntry(entry, schoolId);
        authorizeTeacherWrite(teacherId, schoolId, entry.getAcademicSessionId(),
                entry.getClassId(), entry.getClassName(), entry.getSectionId());
    }

    private void resolveTeacherName(TimetableEntry entry) {
        if (entry.getTeacherId() != null && !entry.getTeacherId().isBlank()) {
            Teacher teacher = teacherRepository.findByTeacherIdAndSchoolId(entry.getTeacherId(), securityUtil.getSchoolId())
                    .orElseThrow(() -> new NoSuchElementException("Teacher not found: " + entry.getTeacherId()));
            entry.setTeacherName(teacher.getName());
        } else {
            entry.setTeacherName(null);
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return obj.toString();
        }
    }
}
