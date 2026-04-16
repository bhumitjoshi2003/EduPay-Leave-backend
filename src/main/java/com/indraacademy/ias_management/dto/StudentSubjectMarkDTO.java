package com.indraacademy.ias_management.dto;

/**
 * One student's mark for a specific subject in a specific exam.
 * Used by GET /api/marks/exam/{examSubjectEntryId}/students (mark entry mode A).
 * marksObtained is null if marks have not been entered yet.
 */
public class StudentSubjectMarkDTO {

    private String studentId;
    private String studentName;
    private Double marksObtained;

    public StudentSubjectMarkDTO(String studentId, String studentName, Double marksObtained) {
        this.studentId = studentId;
        this.studentName = studentName;
        this.marksObtained = marksObtained;
    }

    public String getStudentId() { return studentId; }
    public String getStudentName() { return studentName; }
    public Double getMarksObtained() { return marksObtained; }
}
