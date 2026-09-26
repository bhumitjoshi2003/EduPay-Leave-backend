package com.indraacademy.ias_management.dto;

import java.util.List;

/**
 * Full results for a student in one exam — returned by
 * GET /api/marks/student/{studentId}/results?session=
 *
 * percentage/overallRank/grade/passed are null while the result is incomplete (a mark is missing).
 * overallRank is the competition rank by percentage within the same exam, class and section.
 */
public class ExamResultDTO {

    private Long examId;
    private String examName;
    private String className;
    private String session;
    private String studentName;
    private List<SubjectResultDTO> subjects;
    private Double totalMarksObtained;
    private Double totalMaxMarks;
    private Double percentage;
    private Integer overallRank;

    public ExamResultDTO(Long examId, String examName, String className, String session,
                         String studentName, List<SubjectResultDTO> subjects,
                         Double totalMarksObtained, Double totalMaxMarks,
                         Double percentage, Integer overallRank) {
        this.examId = examId;
        this.examName = examName;
        this.className = className;
        this.session = session;
        this.studentName = studentName;
        this.subjects = subjects;
        this.totalMarksObtained = totalMarksObtained;
        this.totalMaxMarks = totalMaxMarks;
        this.percentage = percentage;
        this.overallRank = overallRank;
    }

    public Long getExamId() { return examId; }
    public String getExamName() { return examName; }
    public String getClassName() { return className; }
    public String getSession() { return session; }
    public String getStudentName() { return studentName; }
    public List<SubjectResultDTO> getSubjects() { return subjects; }
    public Double getTotalMarksObtained() { return totalMarksObtained; }
    public Double getTotalMaxMarks() { return totalMaxMarks; }
    public Double getPercentage() { return percentage; }
    public Integer getOverallRank() { return overallRank; }

    // ── Results Phase 1: canonical result metadata (see ResultCalculator) ──
    /** DRAFT or PUBLISHED. Students/parents only ever receive PUBLISHED exams. */
    private String resultStatus;
    /** False while any applicable subject has no mark; percentage/grade/passed/rank are then null. */
    private boolean complete;
    private int marksMissing;
    private String grade;
    private Boolean passed;

    public String getResultStatus() { return resultStatus; }
    public void setResultStatus(String resultStatus) { this.resultStatus = resultStatus; }
    public boolean isComplete() { return complete; }
    public void setComplete(boolean complete) { this.complete = complete; }
    public int getMarksMissing() { return marksMissing; }
    public void setMarksMissing(int marksMissing) { this.marksMissing = marksMissing; }
    public String getGrade() { return grade; }
    public void setGrade(String grade) { this.grade = grade; }
    public Boolean getPassed() { return passed; }
    public void setPassed(Boolean passed) { this.passed = passed; }
}
