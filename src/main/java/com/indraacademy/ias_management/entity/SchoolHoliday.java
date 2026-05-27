package com.indraacademy.ias_management.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;

@Entity
@Data
@Table(name = "school_holidays",
    uniqueConstraints = @UniqueConstraint(columnNames = {"school_id", "date"}),
    indexes = {
        @Index(name = "idx_holiday_school_date", columnList = "school_id, date")
    })
public class SchoolHoliday {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @JsonFormat(pattern = "yyyy-MM-dd")
    @Column(nullable = false)
    private LocalDate date;

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
