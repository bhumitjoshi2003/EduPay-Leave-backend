package com.indraacademy.ias_management.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;

@Entity
@Data
@Table(name = "school_holidays",
    indexes = {
        @Index(name = "idx_holiday_school_start", columnList = "school_id, start_date"),
        @Index(name = "idx_holiday_school_end", columnList = "school_id, end_date")
    })
public class SchoolHoliday {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @JsonFormat(pattern = "yyyy-MM-dd")
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @JsonFormat(pattern = "yyyy-MM-dd")
    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(nullable = false)
    private String name;

    /** NATIONAL, REGIONAL, SCHOOL, EXAM_BREAK, VACATION */
    @Column(name = "holiday_type", length = 30)
    private String holidayType;

    /** If true, applies to all classes. If false, may be class-specific (future use). */
    @Column(name = "affects_all", nullable = false)
    private boolean affectsAll = true;

    @Column(name = "academic_year", length = 20)
    private String academicYear;

    public SchoolHoliday() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }

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
