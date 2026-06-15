package com.indraacademy.ias_management.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** One row in the bulk mark-save request body. */
public class MarkEntryRequest {

    @NotBlank(message = "Student ID is required")
    private String studentId;

    @NotNull(message = "Exam subject entry ID is required")
    private Long examSubjectEntryId;

    @NotNull(message = "Marks obtained is required")
    @Min(value = 0, message = "Marks cannot be negative")
    private Double marksObtained;

    public MarkEntryRequest() {}

    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }

    public Long getExamSubjectEntryId() { return examSubjectEntryId; }
    public void setExamSubjectEntryId(Long examSubjectEntryId) { this.examSubjectEntryId = examSubjectEntryId; }

    public Double getMarksObtained() { return marksObtained; }
    public void setMarksObtained(Double marksObtained) { this.marksObtained = marksObtained; }
}
