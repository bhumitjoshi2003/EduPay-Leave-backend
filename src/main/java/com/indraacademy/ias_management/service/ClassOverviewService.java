package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.ClassOverviewDTO;
import com.indraacademy.ias_management.dto.ReportCardTemplateDTO;
import com.indraacademy.ias_management.dto.WeightedGroupResultDTO.StudentGroupResultDTO;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentEnrollment;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.repository.AcademicSessionRepository;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.StudentEnrollmentRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Phase 7: Builds the per-class performance overview by joining pre-computed
 * AssessmentGroupResult records with the class student list.
 * No full report card assembly — reads cached weighted scores only.
 */
@Service
public class ClassOverviewService {

    private static final Logger log = LoggerFactory.getLogger(ClassOverviewService.class);

    @Autowired private ReportCardTemplateService    templateService;
    @Autowired private WeightageCalculationEngine   engine;
    @Autowired private StudentRepository            studentRepository;
    @Autowired private SecurityUtil                 securityUtil;
    @Autowired private SchoolClassRepository        schoolClassRepository;
    @Autowired private AcademicSessionRepository    academicSessionRepository;
    @Autowired private StudentEnrollmentRepository  studentEnrollmentRepository;

    public ClassOverviewDTO getClassOverview(Long templateId, String session, String className) {
        return getClassOverview(templateId, session, className, null);
    }

    /** @param sectionId when non-null, restricts to students in that section only — used for a
     *  section-scoped class-teacher so they only ever see the overview for their own section,
     *  not the whole class. Null (ADMIN, or a class with no sections) returns the whole class. */
    public ClassOverviewDTO getClassOverview(Long templateId, String session, String className, Long sectionId) {
        Long schoolId = securityUtil.getSchoolId();

        // ── Template → assessmentGroupId + gradingSystem ───────────────────
        ReportCardTemplateDTO template = templateService.getTemplate(templateId);
        Long groupId = template.getAssessmentGroupId();
        String gradingSystem = (template.getGradingOverride() != null
                && !template.getGradingOverride().isBlank())
                ? template.getGradingOverride() : "CBSE";

        // ── Students in class ──────────────────────────────────────────────
        // Live ACTIVE roster is the floor (unchanged default for schools with no enrollment
        // data). E6E: unioned with every student realized-enrolled in this class(+section) at
        // any point during the session, so a promoted/transferred/withdrawn student's historical
        // performance remains part of the class overview for that session.
        Map<String, Student> roster = new LinkedHashMap<>();
        List<Student> liveStudents = (sectionId != null)
                ? studentRepository.findByClassNameAndSectionIdAndStatusAndSchoolId(className, sectionId, StudentStatus.ACTIVE, schoolId)
                : studentRepository.findByClassNameAndStatusAndSchoolId(className, StudentStatus.ACTIVE, schoolId);
        liveStudents.forEach(s -> roster.put(s.getStudentId(), s));
        augmentRosterWithHistoricalEnrollment(roster, schoolId, className, sectionId, session);
        List<Student> classStudents = new ArrayList<>(roster.values());
        if (classStudents.isEmpty()) {
            return ClassOverviewDTO.empty(className, session, template.getName());
        }

        List<String> studentIds = classStudents.stream()
                .map(Student::getStudentId)
                .collect(Collectors.toList());
        Map<String, String> nameMap = classStudents.stream()
                .collect(Collectors.toMap(Student::getStudentId, Student::getName));

        // ── Compute weighted results on-the-fly and persist for caching ────
        List<StudentGroupResultDTO> computed =
                engine.computeAndRankForClass(studentIds, nameMap, groupId, session);

        // ── Build student summaries ────────────────────────────────────────
        List<ClassOverviewDTO.StudentSummaryDTO> students = new ArrayList<>();
        for (StudentGroupResultDTO r : computed) {
            double pct    = round1(r.getWeightedPercentage());
            String grade  = gradeFromPct(pct, gradingSystem);
            boolean passed = GradingPolicy.passed(pct);
            students.add(new ClassOverviewDTO.StudentSummaryDTO(
                    r.getStudentId(), r.getStudentName(), pct, grade, r.getRank(), passed));
        }

        // ── Statistics ─────────────────────────────────────────────────────
        int total     = students.size();
        int passCount = (int) students.stream().filter(ClassOverviewDTO.StudentSummaryDTO::isPassed).count();
        int failCount = total - passCount;
        double classAvg = computed.stream()
                .mapToDouble(StudentGroupResultDTO::getWeightedPercentage)
                .average()
                .orElse(0.0);
        classAvg = round1(classAvg);

        // ── Grade distribution (ordered) ───────────────────────────────────
        Map<String, Integer> gradeDist = new LinkedHashMap<>();
        for (String g : orderedGrades(gradingSystem)) gradeDist.put(g, 0);
        for (ClassOverviewDTO.StudentSummaryDTO s : students) {
            if (!"—".equals(s.getGrade())) {
                gradeDist.merge(s.getGrade(), 1, Integer::sum);
            }
        }
        // Remove zeroes for a clean response
        gradeDist.entrySet().removeIf(e -> e.getValue() == 0);

        log.info("Class overview: class={} session={} total={} pass={} avg={}",
                className, session, total, passCount, classAvg);

        return new ClassOverviewDTO(className, session, template.getName(),
                total, passCount, failCount, classAvg, gradeDist, students);
    }

    /** See getClassOverview's call site. Mutates {@code roster} in place; no-ops gracefully when
     *  the class/session can't be resolved to a tenant SchoolClass/AcademicSession (leaves the
     *  live-only roster untouched, exactly as before E6E). */
    private void augmentRosterWithHistoricalEnrollment(
            Map<String, Student> roster, Long schoolId, String className, Long sectionId, String session) {
        Long classId = schoolClassRepository.findBySchoolIdAndName(schoolId, className).map(SchoolClass::getId).orElse(null);
        if (classId == null) return;
        academicSessionRepository.findBySchoolIdAndLabel(schoolId, session).ifPresent(as -> {
            List<StudentEnrollment> rows = (sectionId != null)
                    ? studentEnrollmentRepository.findRealizedByAcademicSessionAndClassAndSectionOverlappingRange(
                            schoolId, as.getId(), classId, sectionId, as.getStartDate(), as.getEndDate())
                    : studentEnrollmentRepository.findRealizedByAcademicSessionAndClassOverlappingRange(
                            schoolId, as.getId(), classId, as.getStartDate(), as.getEndDate());
            for (StudentEnrollment row : rows) {
                roster.computeIfAbsent(row.getStudentId(),
                        sid -> studentRepository.findByStudentIdAndSchoolId(sid, schoolId).orElse(null));
            }
        });
        roster.values().removeIf(Objects::isNull);
    }

    // ── Grade helpers ──────────────────────────────────────────────────────

    private String gradeFromPct(double pct, String system) {
        return GradingPolicy.grade(pct, system);
    }

    private List<String> orderedGrades(String system) {
        return switch (system != null ? system : "CBSE") {
            case "LETTER" -> List.of("A+", "A", "B+", "B", "C+", "C", "D", "F");
            default        -> List.of("A1", "A2", "B1", "B2", "C1", "C2", "D", "E");
        };
    }

    private double round1(double v) { return Math.round(v * 10.0) / 10.0; }
}
