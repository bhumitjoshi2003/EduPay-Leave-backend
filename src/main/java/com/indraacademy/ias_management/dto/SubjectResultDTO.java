package com.indraacademy.ias_management.dto;

import java.time.LocalDate;

/**
 * Per-subject result within an exam — used inside ExamResultDTO.
 * rank = 0 means the student has no mark entered (null marksObtained).
 */
public class SubjectResultDTO {

    private String subjectName;
    private Integer maxMarks;
    private LocalDate examDate;
    private Double marksObtained;   // null if not yet entered
    private Double classAverage;
    private Integer rank;           // 0 = not ranked (mark absent)

    public SubjectResultDTO(String subjectName, Integer maxMarks, LocalDate examDate,
                            Double marksObtained, Double classAverage, Integer rank) {
        this.subjectName = subjectName;
        this.maxMarks = maxMarks;
        this.examDate = examDate;
        this.marksObtained = marksObtained;
        this.classAverage = classAverage;
        this.rank = rank;
    }

    public String getSubjectName() { return subjectName; }
    public Integer getMaxMarks() { return maxMarks; }
    public LocalDate getExamDate() { return examDate; }
    public Double getMarksObtained() { return marksObtained; }
    public Double getClassAverage() { return classAverage; }
    public Integer getRank() { return rank; }
}
