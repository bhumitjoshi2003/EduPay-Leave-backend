package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.ClassTeacherResponsibilityDtos.Request;
import com.indraacademy.ias_management.dto.ClassTeacherResponsibilityDtos.View;
import com.indraacademy.ias_management.entity.ClassTeacherResponsibility;
import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.entity.Section;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.TeacherStatus;
import com.indraacademy.ias_management.repository.ClassTeacherResponsibilityRepository;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SectionRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
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
 * Phase F4: ADMIN CRUD for session-scoped class-teacher configuration. This table is
 * configuration/history only — it is never read by {@link TeacherClassScopeService}, and nothing
 * here ever writes {@code Teacher.classTeacher}/{@code classTeacherSectionId}. The only path from
 * "configured" to "live" is the explicit {@link ClassTeacherActivationService}.
 *
 * <p>Class/section canonical resolution here deliberately mirrors {@link TimetableService}'s own
 * {@code requireOwnedClass}/{@code resolveAndRequireSection} rather than sharing a common helper
 * — extracting one would require also rewriting TimetableServiceTest's already-approved mocks for
 * no functional benefit at this scope. If a third caller ever needs this logic, that's the point
 * to extract it.
 */
@Service
public class ClassTeacherResponsibilityService {

    private static final Logger log = LoggerFactory.getLogger(ClassTeacherResponsibilityService.class);

    @Autowired private ClassTeacherResponsibilityRepository responsibilityRepository;
    @Autowired private TimetableSessionAccessService sessionAccess;
    @Autowired private SchoolClassRepository schoolClassRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private AuditService auditService;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<View> list(Long academicSessionId) {
        Long schoolId = securityUtil.getSchoolId();
        sessionAccess.requireOwnedSession(schoolId, academicSessionId);
        return responsibilityRepository.findByAcademicSessionIdAndSchoolId(academicSessionId, schoolId).stream()
                .map(r -> toView(r, schoolId))
                .toList();
    }

    @Transactional
    public View create(Request req, HttpServletRequest httpRequest) {
        Long schoolId = securityUtil.getSchoolId();
        // Lock BEFORE validating historical-ness, so a concurrent activation apply() (which also
        // locks first) can never interleave mid-validation.
        sessionAccess.lockWritableOwnedSession(schoolId, req.academicSessionId());

        SchoolClass schoolClass = requireOwnedClass(schoolId, req.classId());
        Section section = resolveAndRequireSection(schoolId, schoolClass, req.sectionId());
        Teacher teacher = requireEligibleTeacher(req.teacherId(), schoolId);

        ClassTeacherResponsibility responsibility = new ClassTeacherResponsibility();
        responsibility.setSchoolId(schoolId);
        responsibility.setAcademicSessionId(req.academicSessionId());
        responsibility.setClassId(schoolClass.getId());
        responsibility.setSectionId(section != null ? section.getId() : null);
        responsibility.setTeacherId(teacher.getTeacherId());

        ClassTeacherResponsibility saved;
        try {
            saved = responsibilityRepository.saveAndFlush(responsibility);
        } catch (DataIntegrityViolationException e) {
            throw new DataIntegrityViolationException(
                    "A class-teacher responsibility already exists for this class/section in this session.", e);
        }

        audit("CREATE_CLASS_TEACHER_RESPONSIBILITY", saved.getId().toString(), null, saved, httpRequest);
        log.info("Class-teacher responsibility created: id={}, sessionId={}, classId={}, sectionId={}, teacherId={}",
                saved.getId(), saved.getAcademicSessionId(), saved.getClassId(), saved.getSectionId(), saved.getTeacherId());
        return toView(saved, schoolClass, section, teacher);
    }

    @Transactional
    public View update(Long id, Request req, HttpServletRequest httpRequest) {
        Long schoolId = securityUtil.getSchoolId();
        ClassTeacherResponsibility existing = responsibilityRepository.findByIdAndSchoolId(id, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Class-teacher responsibility not found: " + id));

        // Fail closed: the requested session must match the row's actual session — an update can
        // never move a row across sessions, and never silently targets whichever is current.
        if (!Objects.equals(existing.getAcademicSessionId(), req.academicSessionId())) {
            throw new DataIntegrityViolationException(
                    "The requested session does not match this responsibility's actual session. "
                            + "This row belongs to session " + existing.getAcademicSessionId() + ".");
        }
        sessionAccess.lockWritableOwnedSession(schoolId, existing.getAcademicSessionId());

        SchoolClass schoolClass = requireOwnedClass(schoolId, req.classId());
        Section section = resolveAndRequireSection(schoolId, schoolClass, req.sectionId());
        Teacher teacher = requireEligibleTeacher(req.teacherId(), schoolId);

        String oldValue = toJson(existing);
        existing.setClassId(schoolClass.getId());
        existing.setSectionId(section != null ? section.getId() : null);
        existing.setTeacherId(teacher.getTeacherId());

        ClassTeacherResponsibility saved;
        try {
            saved = responsibilityRepository.saveAndFlush(existing);
        } catch (DataIntegrityViolationException e) {
            throw new DataIntegrityViolationException(
                    "A class-teacher responsibility already exists for this class/section in this session.", e);
        }

        audit("UPDATE_CLASS_TEACHER_RESPONSIBILITY", saved.getId().toString(), oldValue, saved, httpRequest);
        return toView(saved, schoolClass, section, teacher);
    }

    @Transactional
    public void delete(Long id, Long academicSessionId, HttpServletRequest httpRequest) {
        Long schoolId = securityUtil.getSchoolId();
        ClassTeacherResponsibility existing = responsibilityRepository.findByIdAndSchoolId(id, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Class-teacher responsibility not found: " + id));

        if (!Objects.equals(existing.getAcademicSessionId(), academicSessionId)) {
            throw new DataIntegrityViolationException(
                    "The requested session does not match this responsibility's actual session. "
                            + "This row belongs to session " + existing.getAcademicSessionId() + ".");
        }
        sessionAccess.lockWritableOwnedSession(schoolId, existing.getAcademicSessionId());

        String oldValue = toJson(existing);
        responsibilityRepository.deleteById(id);
        auditService.log(securityUtil.getUsername(), securityUtil.getRole(), "DELETE_CLASS_TEACHER_RESPONSIBILITY",
                "ClassTeacherResponsibility", id.toString(), oldValue, null, httpRequest.getRemoteAddr());
    }

    // ── canonical resolution (mirrors TimetableService — see class Javadoc) ────────────────

    private SchoolClass requireOwnedClass(Long schoolId, Long classId) {
        return schoolClassRepository.findByIdAndSchoolId(classId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Class not found: " + classId));
    }

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

    private Teacher requireEligibleTeacher(String teacherId, Long schoolId) {
        Teacher teacher = teacherRepository.findByTeacherIdAndSchoolId(teacherId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Teacher not found: " + teacherId));
        if (teacher.getStatus() != TeacherStatus.ACTIVE) {
            throw new IllegalArgumentException("Teacher " + teacherId + " is not ACTIVE.");
        }
        return teacher;
    }

    // ── view assembly: configured vs. live, side by side ────────────────────────────────────

    private View toView(ClassTeacherResponsibility r, Long schoolId) {
        SchoolClass schoolClass = schoolClassRepository.findByIdAndSchoolId(r.getClassId(), schoolId).orElse(null);
        Section section = r.getSectionId() != null
                ? sectionRepository.findByIdAndSchoolId(r.getSectionId(), schoolId).orElse(null) : null;
        Teacher configuredTeacher = teacherRepository.findByTeacherIdAndSchoolId(r.getTeacherId(), schoolId).orElse(null);
        return toView(r, schoolClass, section, configuredTeacher);
    }

    private View toView(ClassTeacherResponsibility r, SchoolClass schoolClass, Section section, Teacher configuredTeacher) {
        Long schoolId = r.getSchoolId();
        String className = schoolClass != null ? schoolClass.getName() : null;
        Teacher liveTeacher = className == null ? null : lookupLiveTeacher(schoolId, className, r.getSectionId());
        boolean matches = liveTeacher != null && Objects.equals(liveTeacher.getTeacherId(), r.getTeacherId());
        return new View(
                r.getId(), r.getAcademicSessionId(), r.getClassId(), className, r.getSectionId(),
                section != null ? section.getName() : null,
                r.getTeacherId(), configuredTeacher != null ? configuredTeacher.getName() : null,
                liveTeacher != null ? liveTeacher.getTeacherId() : null,
                liveTeacher != null ? liveTeacher.getName() : null,
                matches);
    }

    /** The teacher (if any) whose LIVE Teacher.classTeacher/classTeacherSectionId currently
     *  points at this exact class/section — a point-in-time comparison, never a claim that this
     *  configured row was ever applied. Defensive against a pre-existing duplicate (none found in
     *  PROD as of the F1 audit, but not schema-enforced): the first match is used. */
    private Teacher lookupLiveTeacher(Long schoolId, String className, Long sectionId) {
        List<Teacher> holders = sectionId != null
                ? teacherRepository.findByClassTeacherAndClassTeacherSectionIdAndSchoolId(className, sectionId, schoolId)
                : teacherRepository.findByClassTeacherAndClassTeacherSectionIdIsNullAndSchoolId(className, schoolId);
        return holders.isEmpty() ? null : holders.get(0);
    }

    private void audit(String action, String entityId, String oldValue, ClassTeacherResponsibility saved, HttpServletRequest request) {
        if (oldValue == null) {
            auditService.log(securityUtil.getUsername(), securityUtil.getRole(), action,
                    "ClassTeacherResponsibility", entityId, null, toJson(saved), request.getRemoteAddr());
        } else {
            auditService.logUpdate(securityUtil.getUsername(), securityUtil.getRole(), action,
                    "ClassTeacherResponsibility", entityId, oldValue, toJson(saved), request.getRemoteAddr());
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
