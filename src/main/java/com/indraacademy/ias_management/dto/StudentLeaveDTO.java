package com.indraacademy.ias_management.dto;

public class StudentLeaveDTO {
    private String studentId;
    private String name;

    public StudentLeaveDTO(String studentId, String name) {
        this.studentId = studentId;
        this.name = name;
    }

    public String getStudentId() {
        return studentId;
    }

    public String getName() {
        return name;
    }
}
