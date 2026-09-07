package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.PromotionDecisionRequest;
import com.indraacademy.ias_management.dto.PromotionPreviewDTO;
import com.indraacademy.ias_management.dto.PromotionResultDTO;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SchoolTimeUtil;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.indraacademy.ias_management.dto.PromotionPreviewDTO.*;

/** E2 preview and batch coordinator. StudentYearEndService remains the mutation authority. */
@Service
public class StudentPromotionService {
    private static final Logger log = LoggerFactory.getLogger(StudentPromotionService.class);

    private final StudentRepository students;
    private final StudentEnrollmentRepository enrollments;
    private final AcademicSessionRepository sessions;
    private final SchoolClassRepository classes;
    private final SectionRepository sections;
    private final SchoolRepository schools;
    private final StudentYearEndWorker worker;
    private final SecurityUtil security;
    private final Clock clock;

    public StudentPromotionService(
            StudentRepository students, StudentEnrollmentRepository enrollments,
            AcademicSessionRepository sessions, SchoolClassRepository classes,
            SectionRepository sections, SchoolRepository schools,
            StudentYearEndWorker worker, SecurityUtil security, Clock clock) {
        this.students = students;
        this.enrollments = enrollments;
        this.sessions = sessions;
        this.classes = classes;
        this.sections = sections;
        this.schools = schools;
        this.worker = worker;
        this.security = security;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PromotionPreviewDTO getPromotionPreview(
            Long sourceSessionId, Long targetSessionId, Long classId, String studentId) {
        Long schoolId = security.getSchoolId();
        List<Issue> globalErrors = validateSessionPair(schoolId, sourceSessionId, targetSessionId);
        if (!globalErrors.isEmpty()) {
            return new PromotionPreviewDTO(sourceSessionId, targetSessionId, false,
                    globalErrors, List.of(), uncoveredForFilter(schoolId, studentId, Set.of()));
        }

        AcademicSession targetSession = sessions.findByIdAndSchoolId(targetSessionId, schoolId).orElseThrow();
        School school = schools.findById(schoolId).orElseThrow();
        LocalDate today = LocalDate.now(clock.withZone(SchoolTimeUtil.zoneId(school)));
        StudentEnrollmentStatus proposedStatus = today.isBefore(targetSession.getStartDate())
                ? StudentEnrollmentStatus.PLANNED : StudentEnrollmentStatus.ACTIVE;
        List<SchoolClass> sequence = classes.findBySchoolIdAndActiveOrderByDisplayOrderAsc(schoolId, true);

        Map<String,List<StudentEnrollment>> sourceByStudent = enrollments
                .findBySchoolIdAndAcademicSessionIdOrderByStudentIdAscEffectiveFromAsc(schoolId, sourceSessionId)
                .stream().filter(e -> classId == null || Objects.equals(e.getClassId(), classId))
                .filter(e -> studentId == null || studentId.isBlank() || e.getStudentId().equals(studentId))
                .collect(Collectors.groupingBy(StudentEnrollment::getStudentId, LinkedHashMap::new, Collectors.toList()));
        Map<String,List<StudentEnrollment>> targetByStudent = enrollments
                .findBySchoolIdAndAcademicSessionIdOrderByStudentIdAscEffectiveFromAsc(schoolId, targetSessionId)
                .stream().collect(Collectors.groupingBy(StudentEnrollment::getStudentId));
        Map<String,Student> studentById = students.findByStudentIdInAndSchoolId(
                        new ArrayList<>(sourceByStudent.keySet()), schoolId).stream()
                .collect(Collectors.toMap(Student::getStudentId, Function.identity()));

        List<Candidate> candidates = sourceByStudent.entrySet().stream()
                .map(entry -> previewCandidate(entry.getValue(), targetByStudent.getOrDefault(entry.getKey(), List.of()),
                        studentById.get(entry.getKey()), sequence, schoolId, proposedStatus))
                .sorted(Comparator.comparing(Candidate::sourceClassName,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(Candidate::studentName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
        return new PromotionPreviewDTO(sourceSessionId, targetSessionId, true, List.of(), candidates,
                uncoveredForFilter(schoolId, studentId, sourceByStudent.keySet()));
    }

    /** Intentionally non-transactional: each worker invocation owns REQUIRES_NEW. */
    public PromotionResultDTO executePromotion(PromotionDecisionRequest batch, HttpServletRequest request) {
        Long schoolId = security.getSchoolId();
        StudentYearEndDecision.AuditContext actor = new StudentYearEndDecision.AuditContext(
                security.getUsername(), security.getRole(), request.getRemoteAddr());
        List<PromotionResultDTO.StudentOutcome> outcomes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PromotionDecisionRequest.Decision decision : batch.getDecisions()) {
            if (!seen.add(decision.getStudentId())) {
                outcomes.add(validationOutcome(decision.getStudentId(), "Duplicate student decision in batch"));
                continue;
            }
            StudentYearEndDecision.Request command = new StudentYearEndDecision.Request(
                    schoolId, decision.getStudentId(), batch.getSourceSessionId(),
                    batch.getTargetSessionId(), decision.getExpectedSourceEnrollmentId(),
                    decision.getExpectedSourceClassId(), decision.getAction(),
                    decision.getTargetClassId(), decision.getTargetSectionId(), actor);
            try {
                StudentYearEndDecision.Result result = worker.apply(command);
                outcomes.add(new PromotionResultDTO.StudentOutcome(decision.getStudentId(),
                        result.outcome().name(), result.message(), result.sourceEnrollmentId(),
                        result.targetEnrollmentId(), result.targetEnrollmentStatus() == null
                                ? null : result.targetEnrollmentStatus().name(),
                        result.lifecycleFinalizationPending()));
            } catch (IllegalArgumentException | NoSuchElementException e) {
                outcomes.add(validationOutcome(decision.getStudentId(), e.getMessage()));
            } catch (IllegalStateException e) {
                outcomes.add(new PromotionResultDTO.StudentOutcome(decision.getStudentId(),
                        StudentYearEndDecision.Outcome.CONFLICT.name(), e.getMessage(),
                        decision.getExpectedSourceEnrollmentId(), null, null, false));
            } catch (Exception e) {
                log.error("Year-end decision failed: schoolId={}, studentId={}, type={}",
                        schoolId, decision.getStudentId(), e.getClass().getSimpleName());
                outcomes.add(new PromotionResultDTO.StudentOutcome(decision.getStudentId(),
                        "VALIDATION_ERROR", "Decision could not be applied",
                        decision.getExpectedSourceEnrollmentId(), null, null, false));
            }
        }
        Map<String,Long> summary = outcomes.stream().collect(Collectors.groupingBy(
                PromotionResultDTO.StudentOutcome::code, LinkedHashMap::new, Collectors.counting()));
        return new PromotionResultDTO(outcomes.size(), summary, outcomes);
    }

    @Transactional
    public int fixOrphanedSections() {
        return students.clearOrphanedSections(security.getSchoolId());
    }

    private Candidate previewCandidate(
            List<StudentEnrollment> sourceHistory, List<StudentEnrollment> targetHistory,
            Student student, List<SchoolClass> sequence, Long schoolId,
            StudentEnrollmentStatus proposedStatus) {
        StudentEnrollment source = selectYearEndSource(sourceHistory);
        List<Issue> errors = new ArrayList<>();
        List<Issue> warnings = new ArrayList<>();
        if (student == null) errors.add(issue("STUDENT_NOT_FOUND", "Student row does not exist in this school"));

        int index = -1;
        for (int i=0;i<sequence.size();i++) if (Objects.equals(sequence.get(i).getId(), source.getClassId())) index=i;
        boolean finalClass = index >= 0 && index == sequence.size()-1;
        SchoolClass sourceClass = index < 0 ? null : sequence.get(index);
        SchoolClass promoteTarget = index >= 0 && !finalClass ? sequence.get(index+1) : null;
        if (sourceClass == null) errors.add(issue("INVALID_SOURCE_CLASS", "Source class is not in the active class sequence"));

        boolean initial = source.getStatus() == StudentEnrollmentStatus.ACTIVE && source.getEffectiveUntil() == null;
        String appliedState = appliedState(source, targetHistory);
        if (!initial && "NOT_APPLIED".equals(appliedState)) {
            errors.add(issue("INVALID_SOURCE", "Source enrollment is not open and ACTIVE"));
        }
        if (initial && !targetHistory.isEmpty()) {
            errors.add(issue("CONFLICT", "Target-session enrollment already exists"));
            appliedState = "CONFLICT";
        }

        boolean promoteSectionRequired = promoteTarget != null && !sections
                .findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(schoolId, promoteTarget.getId(), true)
                .isEmpty();
        if (promoteSectionRequired && initial) {
            warnings.add(issue("TARGET_SECTION_SELECTION_REQUIRED",
                    "PROMOTE requires an explicit target section"));
        }
        Long detainSection = null;
        if (sourceClass != null && source.getSectionId() != null) {
            detainSection = sections.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(
                            schoolId, sourceClass.getId(), true).stream()
                    .anyMatch(s -> Objects.equals(s.getId(), source.getSectionId()))
                    ? source.getSectionId() : null;
        }
        if (student != null && (!Objects.equals(student.getClassId(), source.getClassId())
                || !Objects.equals(student.getSectionId(), source.getSectionId()))) {
            warnings.add(issue("PROJECTION_DIFFERS", "Student projection differs from authoritative source enrollment"));
        }

        List<StudentYearEndDecision.Action> available = finalClass
                ? List.of(StudentYearEndDecision.Action.DETAIN, StudentYearEndDecision.Action.PASS_OUT)
                : List.of(StudentYearEndDecision.Action.PROMOTE, StudentYearEndDecision.Action.DETAIN);
        return new Candidate(source.getStudentId(), student == null ? null : student.getName(),
                source.getId(), source.getAcademicSessionId(), source.getClassId(), source.getClassNameSnapshot(),
                source.getSectionId(), source.getSectionNameSnapshot(), available,
                finalClass ? StudentYearEndDecision.Action.PASS_OUT : StudentYearEndDecision.Action.PROMOTE,
                promoteTarget == null ? null : promoteTarget.getId(),
                promoteTarget == null ? null : promoteTarget.getName(),
                sourceClass == null ? null : sourceClass.getId(), source.getClassNameSnapshot(),
                promoteSectionRequired, null, detainSection, proposedStatus,
                List.copyOf(errors), List.copyOf(warnings), appliedState);
    }

    private StudentEnrollment selectYearEndSource(List<StudentEnrollment> history) {
        return history.stream().filter(e -> e.getStatus() == StudentEnrollmentStatus.ACTIVE
                        && e.getEffectiveUntil() == null).findFirst()
                .orElseGet(() -> history.stream()
                        .filter(e -> e.getStatus() == StudentEnrollmentStatus.CLOSED)
                        .filter(e -> e.getClosureReason() == StudentEnrollmentClosureReason.SESSION_COMPLETED
                                || e.getClosureReason() == StudentEnrollmentClosureReason.GRADUATED)
                        .findFirst().orElse(history.getLast()));
    }

    private String appliedState(StudentEnrollment source, List<StudentEnrollment> targetHistory) {
        if (source.getStatus() != StudentEnrollmentStatus.CLOSED) return "NOT_APPLIED";
        if (source.getClosureReason() == StudentEnrollmentClosureReason.GRADUATED) return "ALREADY_APPLIED:PASS_OUT";
        if (source.getClosureReason() != StudentEnrollmentClosureReason.SESSION_COMPLETED || targetHistory.size() != 1)
            return "CONFLICT";
        return Objects.equals(source.getClassId(), targetHistory.getFirst().getClassId())
                ? "ALREADY_APPLIED:DETAIN" : "ALREADY_APPLIED:PROMOTE";
    }

    private List<Issue> validateSessionPair(Long schoolId, Long sourceId, Long targetId) {
        List<Issue> errors = new ArrayList<>();
        if (sourceId == null) errors.add(issue("SOURCE_SESSION_REQUIRED", "sourceSessionId is required"));
        if (targetId == null) errors.add(issue("TARGET_SESSION_REQUIRED", "targetSessionId is required"));
        if (!errors.isEmpty()) return errors;
        Optional<AcademicSession> source = sessions.findByIdAndSchoolId(sourceId, schoolId);
        Optional<AcademicSession> target = sessions.findByIdAndSchoolId(targetId, schoolId);
        if (source.isEmpty()) errors.add(issue("SOURCE_SESSION_NOT_FOUND", "Source session not found for school"));
        if (target.isEmpty()) errors.add(issue("TARGET_SESSION_NOT_FOUND", "Target session not found for school"));
        if (!errors.isEmpty()) return errors;
        if (Objects.equals(sourceId, targetId)) errors.add(issue("SESSIONS_MUST_DIFFER", "Source and target sessions must differ"));
        if (!target.orElseThrow().getStartDate().equals(source.orElseThrow().getEndDate().plusDays(1)))
            errors.add(issue("SESSIONS_NOT_CONTIGUOUS", "Target session must start the day after source session ends"));
        School school = schools.findById(schoolId).orElse(null);
        if (school == null) errors.add(issue("SCHOOL_NOT_FOUND", "School not found"));
        else if (LocalDate.now(clock.withZone(SchoolTimeUtil.zoneId(school))).isAfter(target.orElseThrow().getEndDate()))
            errors.add(issue("TARGET_SESSION_ENDED", "Target session has already ended"));
        return errors;
    }

    private List<UncoveredStudent> uncoveredForFilter(Long schoolId, String studentId, Set<String> covered) {
        if (studentId == null || studentId.isBlank() || covered.contains(studentId)) return List.of();
        return students.findByStudentIdAndSchoolId(studentId, schoolId)
                .map(s -> List.of(new UncoveredStudent(s.getStudentId(), s.getName(),
                        "INVALID_SOURCE", "No authoritative source enrollment exists for the selected session")))
                .orElse(List.of());
    }

    private Issue issue(String code, String message) { return new Issue(code, message); }
    private PromotionResultDTO.StudentOutcome validationOutcome(String studentId, String message) {
        return new PromotionResultDTO.StudentOutcome(studentId, "VALIDATION_ERROR",
                message == null ? "Validation failed" : message, null, null, null, false);
    }
}
