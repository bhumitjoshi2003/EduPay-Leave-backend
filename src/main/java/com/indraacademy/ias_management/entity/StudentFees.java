package com.indraacademy.ias_management.entity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.List;

@Entity
@Table(name = "student_fees")
@Data
public class StudentFees {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_id")
    private String studentId;

    @Column(name = "class_name")
    private String className;

    @Column(name = "month")
    private Integer month;

    @Column(name = "paid")
    private Boolean paid;

    @Column(name = "takes_bus")
    private Boolean takesBus;

    @Column(name = "year")
    private String year;

    @Column(name = "distance")
    private Double distance;

    public StudentFees() { }

    public StudentFees(String studentId, String className, Integer month, Boolean paid, Boolean takesBus, String year, Double distance) {
        this.studentId = studentId;
        this.className = className;
        this.month = month;
        this.paid = paid;
        this.takesBus = takesBus;
        this.year = year;
        this.distance = distance;
    }

    public Long getId() {
        return id;
    }

    public String getStudentId() {
        return studentId;
    }

    public String getClassName() {
        return className;
    }

    public Integer getMonth() {
        return month;
    }

    public Boolean getPaid() {
        return paid;
    }

    public Boolean getTakesBus() {
        return takesBus;
    }

    public String getYear() {
        return year;
    }

    public Double getDistance() {
        return distance;
    }

    public void setDistance(Double distance) {
        this.distance = distance;
    }

    public void setStudentId(String studentId) {
        this.studentId = studentId;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public void setMonth(Integer month) {
        this.month = month;
    }

    public void setPaid(Boolean paid) {
        this.paid = paid;
    }

    public void setTakesBus(Boolean takesBus) {
        this.takesBus = takesBus;
    }

    public void setYear(String year) {
        this.year = year;
    }
}
