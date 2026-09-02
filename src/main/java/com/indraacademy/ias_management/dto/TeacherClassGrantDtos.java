package com.indraacademy.ias_management.dto;

import jakarta.validation.constraints.NotBlank;

public class TeacherClassGrantDtos {

    public record CreateRequest(
            @NotBlank(message = "Teacher is required.") String teacherId,
            @NotBlank(message = "Class is required.") String className,
            Long sectionId
    ) {}
}
