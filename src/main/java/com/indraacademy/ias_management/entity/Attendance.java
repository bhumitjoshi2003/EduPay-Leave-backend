package com.indraacademy.ias_management.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Data
@Table(name = "attendance")
public class Attendance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String studentId;
    private boolean chargePaid;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate absentDate;
    private String className;

    public Attendance() {}

    public Attendance(boolean chargePaid, LocalDate absentDate, String className) {
        this.chargePaid = chargePaid;
        this.absentDate = absentDate;
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

    public LocalDate getAbsentDate() {
        return absentDate;
    }

    public void setAbsentDate(LocalDate absentDate) {
        this.absentDate = absentDate;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }
}
