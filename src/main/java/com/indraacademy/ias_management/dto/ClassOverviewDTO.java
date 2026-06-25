package com.indraacademy.ias_management.dto;

import java.util.List;
import java.util.Map;

/**
 * Phase 7: Lightweight per-class performance overview.
 * Used by the Class Overview page (teacher/admin facing).
 */
public class ClassOverviewDTO {

    private String className;
    private String session;
    private String templateName;
    private int    totalStudents;
    private int    passCount;
    private int    failCount;
    private double classAverage;
    /** Grade → count, ordered by descending grade (A1 first, E last). */
    private Map<String, Integer>      gradeDistribution;
    private List<StudentSummaryDTO>   students;

    public ClassOverviewDTO(String className, String session, String templateName,
                             int totalStudents, int passCount, int failCount,
                             double classAverage,
                             Map<String, Integer> gradeDistribution,
                             List<StudentSummaryDTO> students) {
        this.className        = className;
        this.session          = session;
        this.templateName     = templateName;
        this.totalStudents    = totalStudents;
        this.passCount        = passCount;
        this.failCount        = failCount;
        this.classAverage     = classAverage;
        this.gradeDistribution = gradeDistribution;
        this.students         = students;
    }

    public static ClassOverviewDTO empty(String className, String session, String templateName) {
        return new ClassOverviewDTO(className, session, templateName,
                0, 0, 0, 0.0, Map.of(), List.of());
    }

    // ── Getters ────────────────────────────────────────────────────────────
    public String  getClassName()               { return className; }
    public String  getSession()                 { return session; }
    public String  getTemplateName()            { return templateName; }
    public int     getTotalStudents()           { return totalStudents; }
    public int     getPassCount()               { return passCount; }
    public int     getFailCount()               { return failCount; }
    public double  getClassAverage()            { return classAverage; }
    public Map<String, Integer> getGradeDistribution() { return gradeDistribution; }
    public List<StudentSummaryDTO> getStudents()        { return students; }

    // ── Inner DTO ──────────────────────────────────────────────────────────

    public static class StudentSummaryDTO {
        private String  studentId;
        private String  studentName;
        private double  percentage;
        private String  grade;
        private int     rank;
        private boolean passed;

        public StudentSummaryDTO(String studentId, String studentName,
                                  double percentage, String grade, int rank, boolean passed) {
            this.studentId   = studentId;
            this.studentName = studentName;
            this.percentage  = percentage;
            this.grade       = grade;
            this.rank        = rank;
            this.passed      = passed;
        }

        public String  getStudentId()   { return studentId; }
        public String  getStudentName() { return studentName; }
        public double  getPercentage()  { return percentage; }
        public String  getGrade()       { return grade; }
        public int     getRank()        { return rank; }
        public boolean isPassed()       { return passed; }
    }
}
