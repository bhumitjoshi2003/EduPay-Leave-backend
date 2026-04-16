package com.indraacademy.ias_management.dto;

import java.util.List;

/**
 * Full results for a student in one exam — returned by
 * GET /api/marks/student/{studentId}/results?session=
 *
 * overallRank = 0 means the student has no marks entered for this exam.
 */
public class ExamResultDTO {

    private Long examId;
    private String examName;
    private String className;
    private String session;
    private List<SubjectResultDTO> subjects;
    private Double totalMarksObtained;
    private Double totalMaxMarks;
    private Double percentage;
    private Integer overallRank;

    public ExamResultDTO(Long examId, String examName, String className, String session,
                         List<SubjectResultDTO> subjects, Double totalMarksObtained,
                         Double totalMaxMarks, Double percentage, Integer overallRank) {
        this.examId = examId;
        this.examName = examName;
        this.className = className;
        this.session = session;
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
    public List<SubjectResultDTO> getSubjects() { return subjects; }
    public Double getTotalMarksObtained() { return totalMarksObtained; }
    public Double getTotalMaxMarks() { return totalMaxMarks; }
    public Double getPercentage() { return percentage; }
    public Integer getOverallRank() { return overallRank; }
}
