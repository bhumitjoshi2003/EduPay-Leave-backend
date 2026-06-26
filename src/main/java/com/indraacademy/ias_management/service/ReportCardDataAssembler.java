package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.ReportCardDataDTO;
import com.indraacademy.ias_management.dto.ReportCardTemplateDTO;
import com.indraacademy.ias_management.dto.WeightedGroupResultDTO;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.ReportCardTemplate;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.NoSuchElementException;

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

    @Autowired private StudentRepository studentRepo;
    @Autowired private SchoolRepository schoolRepo;
    @Autowired private ReportCardTemplateRepository templateRepo;
    @Autowired private ReportCardTemplateSectionRepository sectionRepo;
    @Autowired private AcademicSessionRepository sessionRepo;
    @Autowired private AttendanceRepository attendanceRepo;
    @Autowired private WeightageCalculationEngine weightageEngine;
    @Autowired private ReportCardTemplateService templateService;
    @Autowired private RemarksService remarksService;
    @Autowired private SecurityUtil securityUtil;

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

        // 5. Resolve grading system (template override → school default)
        String gradingSystem = (template.getGradingOverride() != null && !template.getGradingOverride().isBlank())
                ? template.getGradingOverride()
                : (school.getGradingSystem() != null ? school.getGradingSystem() : "PERCENTAGE");

        // Assemble DTO
        ReportCardDataDTO dto = new ReportCardDataDTO();

        // Student fields
        dto.setStudentId(student.getStudentId());
        dto.setStudentName(student.getName());
        dto.setClassName(student.getClassName());
        dto.setSectionName(student.getSectionName());
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
            dto.setAttendance(buildAttendanceBlock(studentId, student.getClassName(), session, schoolId));
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

    // ── Private helpers ───────────────────────────────────────────────────

    private ReportCardDataDTO.AttendanceBlock buildAttendanceBlock(
            String studentId, String className, String sessionLabel, Long schoolId) {

        // Resolve session dates
        AcademicSession academicSession = sessionRepo.findBySchoolIdAndLabel(schoolId, sessionLabel)
                .orElse(null);

        LocalDate start = (academicSession != null) ? academicSession.getStartDate() : LocalDate.now().withDayOfYear(1);
        LocalDate end = (academicSession != null) ? academicSession.getEndDate() : LocalDate.now();
        // Cap end to today so we don't count future days
        if (end.isAfter(LocalDate.now())) {
            end = LocalDate.now();
        }

        long workingDays = attendanceRepo.countDistinctWorkingDays(className, schoolId, start, end);
        // Each row in Attendance table = one absence day for a student
        long absentDays = attendanceRepo.countByStudentIdAndSchoolIdAndDateBetween(studentId, schoolId, start, end);
        long presentDays = Math.max(0, workingDays - absentDays);
        double pct = workingDays > 0 ? (presentDays * 100.0 / workingDays) : 0.0;

        return new ReportCardDataDTO.AttendanceBlock((int) workingDays, (int) presentDays, pct);
    }
}
