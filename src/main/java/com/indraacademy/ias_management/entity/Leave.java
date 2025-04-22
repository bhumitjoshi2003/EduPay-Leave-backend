package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;

@Entity
@Table(name = "leaves")
@Data
public class Leave {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "student_id", nullable = false)
    private String studentId;

    @Column(name = "student_name", nullable = false)
    private String studentName;

    @Column(name = "leave_date", nullable = false)
    private String leaveDate;

    @Column(name = "class_name", nullable = false)
    private String className;

    @Column(name = "reason", nullable = false, columnDefinition = "TEXT")
    private String reason;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime appliedDate;

    public Leave(){}

    public Leave(String studentId, String studentName, String leaveDate, String reason, String className) {
        this.studentId = studentId;
        this.studentName = studentName;
        this.leaveDate = leaveDate;
        this.reason = reason;
        this.className = className;
    }

    public Long getId() {
        return id;
    }

    public String getStudentId() {
        return studentId;
    }

    public String getLeaveDate() {
        return leaveDate;
    }

    public String getReason() {
        return reason;
    }

    public String getClassName() {
        return className;
    }

    public LocalDateTime getAppliedDate() {
        return appliedDate;
    }

    public void setStudentId(String studentId) {
        this.studentId = studentId;
    }

    public void setLeaveDate(String leaveDate) {
        this.leaveDate = leaveDate;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public void setAppliedDate(LocalDateTime appliedDate) { this.appliedDate = appliedDate; }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getStudentName() { return studentName; }

    public void setStudentName(String studentName) { this.studentName = studentName; }
}
