package com.indraacademy.ias_management.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public class SchoolHolidayDTO {

    private Long id;

    @NotNull(message = "Start date is required")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    @NotNull(message = "End date is required")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;

    @NotBlank(message = "Holiday name is required")
    @Size(max = 255, message = "Holiday name must not exceed 255 characters")
    private String name;

    @NotBlank(message = "Holiday type is required")
    private String holidayType;

    private boolean affectsAll;

    @NotBlank(message = "Academic year is required")
    private String academicYear;

    public SchoolHolidayDTO() {}

    public SchoolHolidayDTO(Long id, LocalDate startDate, LocalDate endDate, String name,
                            String holidayType, boolean affectsAll, String academicYear) {
        this.id = id;
        this.startDate = startDate;
        this.endDate = endDate;
        this.name = name;
        this.holidayType = holidayType;
        this.affectsAll = affectsAll;
        this.academicYear = academicYear;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }

    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getHolidayType() { return holidayType; }
    public void setHolidayType(String holidayType) { this.holidayType = holidayType; }

    public boolean isAffectsAll() { return affectsAll; }
    public void setAffectsAll(boolean affectsAll) { this.affectsAll = affectsAll; }

    public String getAcademicYear() { return academicYear; }
    public void setAcademicYear(String academicYear) { this.academicYear = academicYear; }
}
