package com.indraacademy.ias_management.dto;

import java.util.List;

public class PromotionPreviewDTO {

    private String className;
    private List<StudentLeaveDTO> students;

    public PromotionPreviewDTO(String className, List<StudentLeaveDTO> students) {
        this.className = className;
        this.students = students;
    }

    public String getClassName() { return className; }
    public List<StudentLeaveDTO> getStudents() { return students; }
}
