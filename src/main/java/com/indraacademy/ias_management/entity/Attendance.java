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
    private LocalDate date;
    private String className;

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
}
