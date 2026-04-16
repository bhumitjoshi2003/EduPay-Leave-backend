package com.indraacademy.ias_management.dto;

import java.time.LocalDate;

/**
 * One subject's details + the student's mark for that subject within an exam.
 * Used by GET /api/marks/student/{studentId}/exam/{examConfigId} (mark entry mode B).
 * marksObtained is null if marks have not been entered yet.
 */
public class StudentExamSubjectDTO {

    private Long examSubjectEntryId;
    private String subjectName;
    private Integer maxMarks;
    private LocalDate examDate;
    private Double marksObtained;

    public StudentExamSubjectDTO(Long examSubjectEntryId, String subjectName,
                                 Integer maxMarks, LocalDate examDate, Double marksObtained) {
        this.examSubjectEntryId = examSubjectEntryId;
        this.subjectName = subjectName;
        this.maxMarks = maxMarks;
        this.examDate = examDate;
        this.marksObtained = marksObtained;
    }

    public Long getExamSubjectEntryId() { return examSubjectEntryId; }
    public String getSubjectName() { return subjectName; }
    public Integer getMaxMarks() { return maxMarks; }
    public LocalDate getExamDate() { return examDate; }
    public Double getMarksObtained() { return marksObtained; }
}
