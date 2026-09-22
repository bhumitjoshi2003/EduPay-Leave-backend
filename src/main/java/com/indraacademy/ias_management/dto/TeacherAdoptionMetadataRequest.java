package com.indraacademy.ias_management.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TeacherAdoptionMetadataRequest(
        @NotBlank @Size(max = 50) String appVersionName,
        @Min(1) @Max(2_000_000_000) int appVersionCode) {}
