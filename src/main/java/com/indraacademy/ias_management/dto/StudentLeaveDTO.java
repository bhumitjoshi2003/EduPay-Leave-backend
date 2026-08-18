package com.indraacademy.ias_management.dto;

public class StudentLeaveDTO {
    private String studentId;
    private String name;
    private Long sectionId;

    public StudentLeaveDTO(String studentId, String name) {
        this(studentId, name, null);
    }

    public StudentLeaveDTO(String studentId, String name, Long sectionId) {
        this.studentId = studentId;
        this.name = name;
        this.sectionId = sectionId;
    }

    public String getStudentId() {
        return studentId;
    }

    public String getName() {
        return name;
    }

    public Long getSectionId() { return sectionId; }
}
