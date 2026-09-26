package com.indraacademy.ias_management.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * One student's full result in an exam — used in the class-wide results view.
 * Returned by GET /api/marks/class/{className}/exam/{examConfigId}.
 */
public class ClassStudentResultDTO {

    private String studentId;
    private String studentName;
    private List<SubjectMarkDTO> subjects;
    private Double totalMarksObtained;
    private Double totalMaxMarks;
    private Double percentage;
    private Integer rank;

    public ClassStudentResultDTO(String studentId, String studentName, List<SubjectMarkDTO> subjects,
                                 Double totalMarksObtained, Double totalMaxMarks,
                                 Double percentage, Integer rank) {
        this.studentId = studentId;
        this.studentName = studentName;
        this.subjects = subjects;
        this.totalMarksObtained = totalMarksObtained;
        this.totalMaxMarks = totalMaxMarks;
        this.percentage = percentage;
        this.rank = rank;
    }

    public String getStudentId() { return studentId; }
    public String getStudentName() { return studentName; }
    public List<SubjectMarkDTO> getSubjects() { return subjects; }
    public Double getTotalMarksObtained() { return totalMarksObtained; }
    public Double getTotalMaxMarks() { return totalMaxMarks; }
    public Double getPercentage() { return percentage; }
    public Integer getRank() { return rank; }


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
    /** The student's section (ranks are computed within a section). */
    private String sectionName;
    public String getSectionName() { return sectionName; }
    public void setSectionName(String sectionName) { this.sectionName = sectionName; }

    public static class SubjectMarkDTO {
        private String subjectName;
        private Integer maxMarks;
        private LocalDate examDate;
        private Double marksObtained; // null if not entered

        public SubjectMarkDTO(String subjectName, Integer maxMarks,
                              LocalDate examDate, Double marksObtained) {
            this.subjectName = subjectName;
            this.maxMarks = maxMarks;
            this.examDate = examDate;
            this.marksObtained = marksObtained;
        }

        public String getSubjectName() { return subjectName; }
        public Integer getMaxMarks() { return maxMarks; }
        public LocalDate getExamDate() { return examDate; }
        public Double getMarksObtained() { return marksObtained; }
    }
}
