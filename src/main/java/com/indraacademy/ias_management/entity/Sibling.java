package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "sibling")
@Data
public class Sibling {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_id")
    private String studentId;

    @Column(name = "sibling_id")
    private String siblingId;

    public Sibling(){ }

    public Long getId() {
        return id;
    }

    public Sibling(String studentId, String siblingId) {
        this.studentId = studentId;
        this.siblingId = siblingId;
    }

    public String getStudentId() {
        return studentId;
    }

    public String getSiblingId() {
        return siblingId;
    }

    public void setStudentId(String studentId) {
        this.studentId = studentId;
    }

    public void setSiblingId(String siblingId) {
        this.siblingId = siblingId;
    }
}