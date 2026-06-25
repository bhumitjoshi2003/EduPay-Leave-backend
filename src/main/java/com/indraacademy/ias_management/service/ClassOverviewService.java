package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.ClassOverviewDTO;
import com.indraacademy.ias_management.dto.ReportCardTemplateDTO;
import com.indraacademy.ias_management.entity.AssessmentGroupResult;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.repository.AssessmentGroupResultRepository;
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
    private static final double PASS_THRESHOLD = 33.0;

    @Autowired private ReportCardTemplateService        templateService;
    @Autowired private AssessmentGroupResultRepository  resultRepo;
    @Autowired private StudentRepository                studentRepository;
    @Autowired private SecurityUtil                     securityUtil;

    public ClassOverviewDTO getClassOverview(Long templateId, String session, String className) {
        Long schoolId = securityUtil.getSchoolId();

        // ── Template → assessmentGroupId + gradingSystem ───────────────────
        ReportCardTemplateDTO template = templateService.getTemplate(templateId);
        Long groupId = template.getAssessmentGroupId();
        String gradingSystem = (template.getGradingOverride() != null
                && !template.getGradingOverride().isBlank())
                ? template.getGradingOverride() : "CBSE";

        // ── Students in class ──────────────────────────────────────────────
        List<Student> classStudents = studentRepository
                .findByClassNameAndStatusAndSchoolId(className, StudentStatus.ACTIVE, schoolId);
        if (classStudents.isEmpty()) {
            return ClassOverviewDTO.empty(className, session, template.getName());
        }

        Set<String> classStudentIds = classStudents.stream()
                .map(Student::getStudentId)
                .collect(Collectors.toSet());
        Map<String, String> nameMap = classStudents.stream()
                .collect(Collectors.toMap(Student::getStudentId, Student::getName));

        // ── Pre-computed results (all classes, filtered to this class) ─────
        List<AssessmentGroupResult> allGroupResults = resultRepo
                .findByAssessmentGroupIdAndSessionOrderByWeightedScoreDesc(groupId, session);

        List<AssessmentGroupResult> classResults = allGroupResults.stream()
                .filter(r -> classStudentIds.contains(r.getStudentId()))
                .toList(); // already sorted desc

        Set<String> resultStudentIds = classResults.stream()
                .map(AssessmentGroupResult::getStudentId)
                .collect(Collectors.toSet());

        // ── Build student summaries (ranked within class) ──────────────────
        List<ClassOverviewDTO.StudentSummaryDTO> students = new ArrayList<>();
        int rank = 1;
        for (AssessmentGroupResult r : classResults) {
            double pct = r.getWeightedScore() != null
                    ? round1(r.getWeightedScore().doubleValue()) : 0.0;
            String grade  = gradeFromPct(pct, gradingSystem);
            boolean passed = pct >= PASS_THRESHOLD;
            students.add(new ClassOverviewDTO.StudentSummaryDTO(
                    r.getStudentId(),
                    nameMap.getOrDefault(r.getStudentId(), r.getStudentId()),
                    pct, grade, rank++, passed));
        }
        // Students with no result yet — append without rank
        for (Student s : classStudents) {
            if (!resultStudentIds.contains(s.getStudentId())) {
                students.add(new ClassOverviewDTO.StudentSummaryDTO(
                        s.getStudentId(), s.getName(), 0.0, "—", 0, false));
            }
        }

        // ── Statistics ─────────────────────────────────────────────────────
        int total     = students.size();
        int passCount = (int) students.stream().filter(ClassOverviewDTO.StudentSummaryDTO::isPassed).count();
        int failCount = total - passCount;
        double classAvg = classResults.stream()
                .mapToDouble(r -> r.getWeightedScore() != null ? r.getWeightedScore().doubleValue() : 0.0)
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

    // ── Grade helpers ──────────────────────────────────────────────────────

    private String gradeFromPct(double pct, String system) {
        switch (system != null ? system : "CBSE") {
            case "PERCENTAGE" -> { return String.format("%.0f%%", pct); }
            case "LETTER" -> {
                if (pct >= 90) return "A+";
                if (pct >= 80) return "A";
                if (pct >= 70) return "B+";
                if (pct >= 60) return "B";
                if (pct >= 50) return "C+";
                if (pct >= 40) return "C";
                if (pct >= 33) return "D";
                return "F";
            }
            default -> {  // CBSE
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
    }

    private List<String> orderedGrades(String system) {
        return switch (system != null ? system : "CBSE") {
            case "LETTER" -> List.of("A+", "A", "B+", "B", "C+", "C", "D", "F");
            default        -> List.of("A1", "A2", "B1", "B2", "C1", "C2", "D", "E");
        };
    }

    private double round1(double v) { return Math.round(v * 10.0) / 10.0; }
}
