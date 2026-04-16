package com.indraacademy.ias_management.dto;

/** One row in the bulk mark-save request body. */
public class MarkEntryRequest {

    private String studentId;
    private Long examSubjectEntryId;
    private Double marksObtained;

    public MarkEntryRequest() {}

    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }

    public Long getExamSubjectEntryId() { return examSubjectEntryId; }
    public void setExamSubjectEntryId(Long examSubjectEntryId) { this.examSubjectEntryId = examSubjectEntryId; }

    public Double getMarksObtained() { return marksObtained; }
    public void setMarksObtained(Double marksObtained) { this.marksObtained = marksObtained; }
}
