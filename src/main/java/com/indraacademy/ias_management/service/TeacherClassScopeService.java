package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SectionRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Single, shared source of truth for "is this teacher authorized to act on this
 * class/section/student, and if so, which section are they actually scoped to" — used by every
 * TEACHER-facing, class/section-scoped controller (Attendance, Marks, Report Card, Leave,
 * Student roster, AI workflows) so the rule can never drift out of sync between them.
 *
 * <p><b>The central security guarantee this class provides:</b> a caller's own request
 * parameters (a {@code sectionId} query param, a student's claimed class, anything client-sent)
 * are NEVER trusted to determine a TEACHER's scope. {@link #resolveOwnScope} reads only the
 * authenticated teacher's own {@code Teacher} row. {@link #authorizeAndScopeToClass} and
 * {@link #authorizeAndScopeToStudent} always return the teacher's OWN section as the effective
 * one to filter/act on — a client-supplied sectionId is used only to detect and reject an
 * attempt to widen scope (see the two methods' javadoc), never to grant it.
 *
 * <p>ADMIN/SUPER_ADMIN callers are always allowed and unrestricted here — this class exists to
 * constrain TEACHER-role access only; admin-level authorization is unchanged and enforced
 * elsewhere (role checks on the controller methods themselves).
 */
@Service
public class TeacherClassScopeService {

    public static final String SECTION_REQUIRED_MESSAGE =
            "Your class responsibility requires a section assignment. Please contact the administrator.";
    private static final String NOT_OWN_CLASS_MESSAGE =
            "Teachers can only access their own assigned class.";
    private static final String NOT_OWN_SECTION_MESSAGE =
            "Teachers can only access their own assigned section.";

    @Autowired private TeacherRepository teacherRepository;
    @Autowired private SchoolClassRepository schoolClassRepository;
    @Autowired private SectionRepository sectionRepository;

    /** A teacher's authoritative class+section assignment, resolved fresh from their own
     *  Teacher row — never from a request parameter.
     *
     *  @param className                the teacher's classTeacher value, or null if unassigned
     *  @param sectionId                the teacher's classTeacherSectionId, or null if their
     *                                  class has no configured sections
     *  @param sectionRequiredButMissing true when className's class HAS configured sections but
     *                                  classTeacherSectionId is null — a legacy/ambiguous
     *                                  assignment that must be blocked, never treated as
     *                                  "all sections" */
    public record TeacherScope(String className, Long sectionId, boolean sectionRequiredButMissing) {
        public boolean hasClassResponsibility() { return className != null && !className.isBlank(); }
    }

    /** Outcome of an authorization+scoping check: either denied with a specific reason, or
     *  allowed with the effective sectionId the caller must use (overriding any client input for
     *  TEACHER role; passed through unchanged for ADMIN/SUPER_ADMIN, who are unrestricted). */
    public record ScopedAccess(boolean allowed, String errorMessage, Long effectiveSectionId) {
        public static ScopedAccess allow(Long effectiveSectionId) {
            return new ScopedAccess(true, null, effectiveSectionId);
        }
        public static ScopedAccess deny(String message) {
            return new ScopedAccess(false, message, null);
        }
    }

    public TeacherScope resolveOwnScope(String teacherId, Long schoolId) {
        Teacher teacher = teacherRepository.findByTeacherIdAndSchoolId(teacherId, schoolId).orElse(null);
        String className = teacher != null ? teacher.getClassTeacher() : null;
        if (className == null || className.isBlank()) {
            return new TeacherScope(null, null, false);
        }
        if (classHasSections(className, schoolId)) {
            if (teacher.getClassTeacherSectionId() == null) {
                return new TeacherScope(className, null, true);
            }
            return new TeacherScope(className, teacher.getClassTeacherSectionId(), false);
        }
        // Class has no configured sections — class-only scope, exactly today's original behavior.
        return new TeacherScope(className, null, false);
    }

    public boolean classHasSections(String className, Long schoolId) {
        return schoolClassRepository.findBySchoolIdAndName(schoolId, className)
                .map(sc -> !sectionRepository
                        .findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(schoolId, sc.getId(), true)
                        .isEmpty())
                .orElse(false);
    }

    /**
     * Authorizes and scopes a request that targets a class (optionally narrowed to a section) as
     * a whole — e.g. "give me the attendance roster for class 12". For ADMIN/SUPER_ADMIN, always
     * allowed, and the client-supplied {@code requestedSectionId} is returned unchanged (admins
     * pick their own section freely, exactly as before this change). For TEACHER, the requested
     * className must equal the teacher's own; a legacy ambiguous assignment is blocked with the
     * actionable message; and — critically — the EFFECTIVE section returned is always the
     * teacher's own {@code classTeacherSectionId}, never {@code requestedSectionId}, so a client
     * cannot widen (or narrow away from) their assigned section by sending a different value or
     * omitting it.
     */
    public ScopedAccess authorizeAndScopeToClass(String role, String teacherId, Long schoolId,
                                                  String requestedClassName, Long requestedSectionId) {
        if (!Role.TEACHER.equals(role)) {
            return ScopedAccess.allow(requestedSectionId);
        }
        TeacherScope scope = resolveOwnScope(teacherId, schoolId);
        if (!scope.hasClassResponsibility() || !scope.className().equals(requestedClassName)) {
            return ScopedAccess.deny(NOT_OWN_CLASS_MESSAGE);
        }
        if (scope.sectionRequiredButMissing()) {
            return ScopedAccess.deny(SECTION_REQUIRED_MESSAGE);
        }
        return ScopedAccess.allow(scope.sectionId());
    }

    /**
     * Authorizes and scopes a request that targets one specific student — e.g. "give me this
     * student's attendance summary". For ADMIN/SUPER_ADMIN, always allowed. For TEACHER, the
     * student's own className must equal the teacher's, a legacy ambiguous assignment is
     * blocked, and — when the teacher's class has sections — the student's own sectionId must
     * equal the teacher's assigned section (a Science class-teacher can never look up a Commerce
     * student even by exact studentId).
     */
    public ScopedAccess authorizeAndScopeToStudent(String role, String teacherId, Long schoolId,
                                                    String studentClassName, Long studentSectionId) {
        if (!Role.TEACHER.equals(role)) {
            return ScopedAccess.allow(null);
        }
        TeacherScope scope = resolveOwnScope(teacherId, schoolId);
        if (!scope.hasClassResponsibility() || !scope.className().equals(studentClassName)) {
            return ScopedAccess.deny(NOT_OWN_CLASS_MESSAGE);
        }
        if (scope.sectionRequiredButMissing()) {
            return ScopedAccess.deny(SECTION_REQUIRED_MESSAGE);
        }
        if (scope.sectionId() != null && !Objects.equals(scope.sectionId(), studentSectionId)) {
            return ScopedAccess.deny(NOT_OWN_SECTION_MESSAGE);
        }
        return ScopedAccess.allow(scope.sectionId());
    }
}
