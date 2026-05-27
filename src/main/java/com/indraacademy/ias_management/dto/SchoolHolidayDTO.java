package com.indraacademy.ias_management.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;

public class SchoolHolidayDTO {

    private Long id;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate date;

    private String name;
    private String holidayType;
    private boolean affectsAll;
    private String academicYear;

    public SchoolHolidayDTO() {}

    public SchoolHolidayDTO(Long id, LocalDate date, String name, String holidayType, boolean affectsAll, String academicYear) {
        this.id = id;
        this.date = date;
        this.name = name;
        this.holidayType = holidayType;
        this.affectsAll = affectsAll;
        this.academicYear = academicYear;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getHolidayType() { return holidayType; }
    public void setHolidayType(String holidayType) { this.holidayType = holidayType; }

    public boolean isAffectsAll() { return affectsAll; }
    public void setAffectsAll(boolean affectsAll) { this.affectsAll = affectsAll; }

    public String getAcademicYear() { return academicYear; }
    public void setAcademicYear(String academicYear) { this.academicYear = academicYear; }
}
