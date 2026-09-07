package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.AttendanceSummaryDTO;
import com.indraacademy.ias_management.dto.ReportCardDataDTO;
import com.indraacademy.ias_management.dto.ReportCardTemplateDTO;
import com.indraacademy.ias_management.dto.WeightedGroupResultDTO;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.ReportCardTemplate;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assembles a fully hydrated ReportCardDataDTO by orchestrating:
 *   1. Student data lookup
 *   2. School data lookup
 *   3. Template + sections lookup
 *   4. WeightageCalculationEngine invocation
 *   5. Attendance summary (if ATTENDANCE section is enabled)
 */
@Service
public class ReportCardDataAssembler {

    private static final Logger log = LoggerFactory.getLogger(ReportCardDataAssembler.class);

    @Autowired private StudentRepository studentRepo;
    @Autowired private SchoolRepository schoolRepo;
    @Autowired private ReportCardTemplateRepository templateRepo;
    @Autowired private ReportCardTemplateSectionRepository sectionRepo;
    @Autowired private AcademicSessionRepository sessionRepo;
    @Autowired private AttendanceService attendanceService;
    @Autowired private MarkService markService;
    @Autowired private WeightageCalculationEngine weightageEngine;
    @Autowired private ReportCardTemplateService templateService;
    @Autowired private RemarksService remarksService;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private StudentTemporalMembershipResolver temporalMembershipResolver;
    @Autowired private SchoolClassRepository schoolClassRepo;

    private static final DateTimeFormatter DOB_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    /**
     * Assemble a report card for a student using a specific template and session.
     *
     * @param studentId  student's string PK
     * @param templateId report card template ID
     * @param session    academic session label (e.g. "2024-25")
     */
    @Transactional(readOnly = true)
    public ReportCardDataDTO assemble(String studentId, Long templateId, String session) {
        return assemble(studentId, templateId, session, null);
    }

    /**
     * @param classId optional discriminator (SchoolClass.id) resolving which historical class
     *                context this report card is for, when the student has more than one
     *                legitimate context in this session (see resolveHistoricalContext). Null
     *                when the context is uniquely resolvable without it.
     */
    @Transactional(readOnly = true)
    public ReportCardDataDTO assemble(String studentId, Long templateId, String session, Long classId) {
        Long schoolId = securityUtil.getSchoolId();

        // 1. Load student
        Student student = studentRepo.findByStudentIdAndSchoolId(studentId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Student not found: " + studentId));

        // 2. Load school
        School school = schoolRepo.findById(schoolId)
                .orElseThrow(() -> new NoSuchElementException("School not found: " + schoolId));

        // 3. Load template
        ReportCardTemplate template = templateRepo.findByIdAndSchoolId(templateId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Template not found: " + templateId));

        // 4. Compute weighted result via engine
        WeightedGroupResultDTO weightedResult = weightageEngine
                .computeForStudent(studentId, template.getAssessmentGroupId(), session);

        // 4b. Resolve the class/section this report card's academic context actually belongs to
        // for this session — never student.getClassName() directly. See resolveHistoricalContext.
        HistoricalReportCardContext context = resolveHistoricalContext(studentId, session, classId, student);
        String resolvedClassName = context.className();

        // 5. Resolve grading system (template override → school default)
        String gradingSystem = (template.getGradingOverride() != null && !template.getGradingOverride().isBlank())
                ? template.getGradingOverride()
                : (school.getGradingSystem() != null ? school.getGradingSystem() : "PERCENTAGE");

        // Assemble DTO
        ReportCardDataDTO dto = new ReportCardDataDTO();

        // Student fields
        dto.setStudentId(student.getStudentId());
        dto.setStudentName(student.getName());
        dto.setClassName(resolvedClassName);
        // E6E: historical section now comes from the applicable realized StudentEnrollment
        // segment for this class/session (E6B) rather than a guess — see resolveHistoricalSection.
        // When no enrollment segment applies, falls back to the student's live section only when
        // the resolved class still equals their CURRENT class (no promotion since this session —
        // a genuinely safe, wholly-legacy case), and is left unset otherwise rather than guessed.
        dto.setSectionName(context.sectionName());
        dto.setSession(session);
        dto.setFatherName(student.getFatherName());
        dto.setMotherName(student.getMotherName());
        if (student.getDob() != null) {
            dto.setDateOfBirth(student.getDob().format(DOB_FMT));
        }
        dto.setPhotoUrl(student.getPhotoUrl());

        // School fields
        dto.setSchoolName(school.getName());
        dto.setSchoolLogoUrl(school.getLogoUrl());
        dto.setSchoolAddress(school.getAddress());
        dto.setSchoolPhone(school.getPhone());
        dto.setSchoolEmail(school.getEmail());
        if (school.getBoardType() != null) {
            dto.setBoardType(school.getBoardType().name());
        }
        dto.setAffiliationNumber(school.getAffiliationNumber());
        dto.setSchoolCode(school.getSchoolCode());
        dto.setSchoolCity(school.getCity());
        dto.setReportCardHeaderImageUrl(school.getReportCardHeaderImageUrl());

        // Template
        dto.setTemplate(templateService.getTemplate(templateId));
        dto.setGradingSystem(gradingSystem);

        // Weighted result
        dto.setWeightedResult(weightedResult);

        // Attendance — only if ATTENDANCE section is enabled
        boolean attendanceEnabled = sectionRepo
                .findByTemplateIdAndSectionType(templateId, "ATTENDANCE")
                .map(s -> Boolean.TRUE.equals(s.getEnabled()))
                .orElse(false);

        if (attendanceEnabled) {
            dto.setAttendance(buildAttendanceBlock(studentId, session, schoolId));
        }

        // Remarks — only if the respective sections are enabled
        boolean teacherRemarksEnabled = sectionRepo
                .findByTemplateIdAndSectionType(templateId, "TEACHER_REMARKS")
                .map(s -> Boolean.TRUE.equals(s.getEnabled()))
                .orElse(false);
        boolean principalRemarksEnabled = sectionRepo
                .findByTemplateIdAndSectionType(templateId, "PRINCIPAL_REMARKS")
                .map(s -> Boolean.TRUE.equals(s.getEnabled()))
                .orElse(false);
        boolean coScholasticEnabled = sectionRepo
                .findByTemplateIdAndSectionType(templateId, "CO_SCHOLASTIC")
                .map(s -> Boolean.TRUE.equals(s.getEnabled()))
                .orElse(false);

        if (teacherRemarksEnabled) {
            dto.setTeacherRemarks(remarksService.getStudentRemark(
                    studentId, templateId, session, "TEACHER", schoolId));
        }
        if (principalRemarksEnabled) {
            dto.setPrincipalRemarks(remarksService.getStudentRemark(
                    studentId, templateId, session, "PRINCIPAL", schoolId));
        }
        if (coScholasticEnabled) {
            dto.setCoScholasticGrades(remarksService.getStudentCoScholastic(
                    studentId, templateId, session, schoolId));
        }

        // Overall grade + CGPA
        double overallPct = weightedResult.getWeightedPercentage();
        dto.setOverallGrade(gradeFromPct(overallPct, gradingSystem));
        if ("CBSE".equalsIgnoreCase(gradingSystem)) {
            dto.setCgpa(computeCgpa(weightedResult, gradingSystem));
        }

        return dto;
    }

    // ── Grade helpers ─────────────────────────────────────────────────────

    private String gradeFromPct(double pct, String gradingSystem) {
        switch (gradingSystem == null ? "CBSE" : gradingSystem.toUpperCase()) {
            case "PERCENTAGE": return Math.round(pct) + "%";
            case "LETTER":
                if (pct >= 90) return "A+";
                if (pct >= 80) return "A";
                if (pct >= 70) return "B+";
                if (pct >= 60) return "B";
                if (pct >= 50) return "C+";
                if (pct >= 40) return "C";
                if (pct >= 33) return "D";
                return "F";
            default: // CBSE
                if (pct >= 91) return "A1";
                if (pct >= 81) return "A2";
                if (pct >= 71) return "B1";
                if (pct >= 61) return "B2";
                if (pct >= 51) return "C1";
                if (pct >= 41) return "C2";
                if (pct >= 33) return "D";
                return "E";
        }
    }

    private double cbseGradePoint(String grade) {
        switch (grade) {
            case "A1": return 10.0;
            case "A2": return 9.0;
            case "B1": return 8.0;
            case "B2": return 7.0;
            case "C1": return 6.0;
            case "C2": return 5.0;
            case "D":  return 4.0;
            default:   return 0.0; // E / absent
        }
    }

    private Double computeCgpa(WeightedGroupResultDTO result, String gradingSystem) {
        java.util.List<WeightedGroupResultDTO.SubjectWeightedResultDTO> subjects = result.getSubjectResults();
        if (subjects == null || subjects.isEmpty()) {
            // Fallback to overall percentage when no per-subject data (GROUP_BASED)
            return Math.round(cbseGradePoint(gradeFromPct(result.getWeightedPercentage(), gradingSystem)) * 10.0) / 10.0;
        }
        double sum = 0;
        for (WeightedGroupResultDTO.SubjectWeightedResultDTO s : subjects) {
            sum += cbseGradePoint(gradeFromPct(s.getWeightedPercentage(), gradingSystem));
        }
        double raw = sum / subjects.size();
        return Math.round(raw * 10.0) / 10.0;
    }

    // ── E6E: shared academic-context resolution ───────────────────────────
    //
    // A report card's historical context is student + template + AcademicSession + historical
    // class (+ section). Class discovery is delegated entirely to MarkService/E6D
    // (resolveHistoricalClassNamesForSession — marks ∪ realized enrollment, respecting E6B's
    // legacy/gap classification); this assembler never re-implements that discovery. What IS
    // new here is (a) refusing to arbitrarily pick a class when more than one is legitimately
    // possible — an explicit classId disambiguates instead — and (b) resolving the HISTORICAL
    // SECTION, which E6D's class-name-only resolution doesn't cover, via E6B's enrollment
    // segments directly.

    /** A student's resolved historical report-card context: the exact class(+section) this
     *  report card is for. {@code classId} is the tenant SchoolClass id backing {@code className}
     *  (null if the class name can't be mapped to a current SchoolClass row — e.g. a renamed/
     *  retired class). */
    public record HistoricalReportCardContext(String className, Long classId, Long sectionId, String sectionName) {}

    /** Thrown when a student has more than one legitimate historical class context for the
     *  requested session and the caller supplied no (or a non-matching) classId to disambiguate.
     *  Never resolved by arbitrary selection — see the class Javadoc. */
    public static class ReportCardContextAmbiguousException extends RuntimeException {
        private final Set<String> candidates;
        public ReportCardContextAmbiguousException(Set<String> candidates) {
            super("Multiple historical report-card class contexts exist for this student/session ("
                    + candidates + "). Specify classId to disambiguate.");
            this.candidates = candidates;
        }
        public Set<String> getCandidates() { return candidates; }
    }

    /**
     * Public entry point so callers outside this assembler (e.g. {@code ReportCardController}'s
     * publication-access check) can resolve the same historical context this assembler uses,
     * without needing their own {@code MarkService}/resolver dependencies.
     *
     * @param classId optional discriminator; required only when the student genuinely has more
     *                than one legitimate class context for this session (see
     *                {@link ReportCardContextAmbiguousException}).
     */
    public HistoricalReportCardContext resolveHistoricalContext(String studentId, String session, Long classId) {
        Long schoolId = securityUtil.getSchoolId();
        Student student = studentRepo.findByStudentIdAndSchoolId(studentId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Student not found: " + studentId));
        return resolveHistoricalContext(studentId, session, classId, student);
    }

    private HistoricalReportCardContext resolveHistoricalContext(String studentId, String session, Long classId, Student student) {
        Long schoolId = securityUtil.getSchoolId();

        // Class discovery — entirely E6D's job (marks ∪ realized enrollment for this session,
        // already respecting legacy/gap semantics). Never reimplemented here.
        Set<String> candidateClassNames = markService.resolveHistoricalClassNamesForSession(
                studentId, session, schoolId, student.getClassName());

        String resolvedClassName;
        if (candidateClassNames.isEmpty()) {
            // No marks, no realized enrollment, and E6B classified this as an authoritative gap
            // (legacy fallback was NOT permitted) — there is genuinely nothing to build a report
            // card from. Do not fabricate a context from the live current class.
            throw new NoSuchElementException(
                    "No historical report-card context could be resolved for student " + studentId + " in session " + session);
        } else if (candidateClassNames.size() == 1) {
            resolvedClassName = candidateClassNames.iterator().next();
        } else if (classId != null) {
            String requestedClassName = schoolClassRepo.findByIdAndSchoolId(classId, schoolId)
                    .map(SchoolClass::getName)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown classId: " + classId));
            if (!candidateClassNames.contains(requestedClassName)) {
                throw new IllegalArgumentException(
                        "classId " + classId + " (" + requestedClassName + ") does not match any historical " +
                                "report-card context for student " + studentId + " in session " + session +
                                " (" + candidateClassNames + ").");
            }
            resolvedClassName = requestedClassName;
        } else {
            // Multiple legitimate contexts and no discriminator — never guess (no
            // Set.iterator().next(), no "latest wins"). The caller must supply classId.
            throw new ReportCardContextAmbiguousException(candidateClassNames);
        }

        Long resolvedClassId = schoolClassRepo.findBySchoolIdAndName(schoolId, resolvedClassName)
                .map(SchoolClass::getId).orElse(null);
        HistoricalSection section = resolveHistoricalSection(studentId, schoolId, session, resolvedClassName, student);
        return new HistoricalReportCardContext(resolvedClassName, resolvedClassId, section.sectionId(), section.sectionName());
    }

    private record HistoricalSection(Long sectionId, String sectionName) {}

    /**
     * Historical section for the resolved class/session, via E6B's realized enrollment segments
     * — never the marks/exam domain (which has no section column at all) and never a silent
     * guess from the live Student row unless the resolved class still equals the student's
     * CURRENT class (no promotion since this session — a genuinely safe, wholly-legacy case).
     * <p>Multi-segment same-class case: a section change alone (e.g. mid-session section
     * transfer) must not create a second report card, since the report-card class context is
     * unchanged — but more than one section legitimately applied across the session, and no
     * single one can truthfully represent the whole report. Documented compatibility rule: the
     * section from the segment with the latest {@code effectiveFrom} is shown (the section the
     * student was in most recently within this class/session) — the same "latest segment wins
     * for display" rule E6C/E6D already use, never today's live section.
     */
    private HistoricalSection resolveHistoricalSection(
            String studentId, Long schoolId, String session, String resolvedClassName, Student student) {
        Optional<AcademicSession> sessionOpt = sessionRepo.findBySchoolIdAndLabel(schoolId, session);
        if (sessionOpt.isPresent()) {
            try {
                StudentTemporalMembershipResolver.SessionResolution resolution =
                        temporalMembershipResolver.realizedEnrollmentSegmentsForSession(schoolId, studentId, sessionOpt.get().getId());
                if (resolution.classification() != StudentTemporalMembershipResolver.CoverageClassification.CONFLICT) {
                    List<StudentTemporalMembershipResolver.Segment> matching = resolution.segments().stream()
                            .filter(s -> resolvedClassName.equals(s.classNameSnapshot()))
                            .sorted(Comparator.comparing(StudentTemporalMembershipResolver.Segment::effectiveFrom))
                            .toList();
                    if (!matching.isEmpty()) {
                        StudentTemporalMembershipResolver.Segment latest = matching.get(matching.size() - 1);
                        return new HistoricalSection(latest.sectionId(), latest.sectionNameSnapshot());
                    }
                } else {
                    log.error("Enrollment conflict resolving historical section for student {} session {}: {} — " +
                                    "falling back to legacy section rule.",
                            studentId, session, resolution.conflictReason());
                }
            } catch (RuntimeException e) {
                log.error("Failed to resolve realized enrollment section for student {} session {}", studentId, session, e);
            }
        }
        // No enrollment segment applies (LEGACY_UNCOVERED, or no AcademicSession for this label) —
        // the marks/exam domain has no section column, so the only safe, non-fabricated section
        // is the student's CURRENT one, and only when no promotion has happened since this
        // session (resolved class still equals the live class). Left unset otherwise.
        if (resolvedClassName.equals(student.getClassName())) {
            return new HistoricalSection(student.getSectionId(), student.getSectionName());
        }
        return new HistoricalSection(null, null);
    }

    private ReportCardDataDTO.AttendanceBlock buildAttendanceBlock(
            String studentId, String sessionLabel, Long schoolId) {

        // E6E: the real, tenant-owned AcademicSession is the only source of truth for this
        // range — a missing session must fail clearly rather than guess a Jan-1-to-today window
        // (which silently mixed unrelated data into a report card's attendance figures).
        AcademicSession academicSession = sessionRepo.findBySchoolIdAndLabel(schoolId, sessionLabel)
                .orElseThrow(() -> new NoSuchElementException(
                        "No academic session found for label: " + sessionLabel));

        LocalDate start = academicSession.getStartDate();
        LocalDate end = academicSession.getEndDate();
        // Cap end to today so we don't count future days
        if (end.isAfter(LocalDate.now())) {
            end = LocalDate.now();
        }

        // Delegates to AttendanceService's shared enrollment-aware historical attendance logic
        // (E6C) rather than recomputing working-days/absences against a single class name here —
        // never duplicate attendance membership logic in the report-card module.
        AttendanceSummaryDTO summary = attendanceService.getStudentAttendanceForDateRange(studentId, start, end);

        return new ReportCardDataDTO.AttendanceBlock(
                (int) summary.getTotalWorkingDays(),
                (int) Math.round(summary.getDaysPresent()),
                summary.getAttendancePercentage());
    }
}
