package com.indraacademy.ias_management.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Data
@Table(name = "attendance", indexes = {
    @Index(name = "idx_attendance_class_date",   columnList = "class_name, date"),
    @Index(name = "idx_attendance_student_date", columnList = "student_id, date")
})
public class Attendance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id")
    private Long schoolId;

    private String studentId;
    private boolean chargePaid;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate date;
    private String className;

    @Column(name = "class_id")
    private Long classId;

    @Column(name = "section_id")
    private Long sectionId;

    /** ABSENT, PRESENT, HALF_DAY, LATE, EXCUSED. Null treated as ABSENT for backward compatibility. */
    @Column(name = "status", length = 20)
    private String status;

    /** userId of the teacher/admin who marked this record. */
    @Column(name = "marked_by", length = 100)
    private String markedBy;

    public Attendance() {}

    public Attendance(boolean chargePaid, LocalDate date, String className) {
        this.chargePaid = chargePaid;
        this.date = date;
        this.className = className;
    }

    // Getters and setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getStudentId() {
        return studentId;
    }

    public void setStudentId(String studentId) {
        this.studentId = studentId;
    }

    public boolean isChargePaid() {
        return chargePaid;
    }

    public void setChargePaid(boolean chargePaid) {
        this.chargePaid = chargePaid;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public Long getClassId() { return classId; }
    public void setClassId(Long classId) { this.classId = classId; }

    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getMarkedBy() { return markedBy; }
    public void setMarkedBy(String markedBy) { this.markedBy = markedBy; }

    public Long getSectionId() { return sectionId; }
    public void setSectionId(Long sectionId) { this.sectionId = sectionId; }
}
