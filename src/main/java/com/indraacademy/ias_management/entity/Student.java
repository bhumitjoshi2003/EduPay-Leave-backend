package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "student")
@Data
public class Student {

    @Id
    @Column(name = "student_id")
    private String studentId;

    @Column(name = "name")
    private String name;

    @Column(name = "email")
    private String email;

    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "dob")
    private LocalDate dob;

    @Column(name = "class_name")
    private String className;

    @Column(name = "gender")
    private String gender;

    @Column(name = "father_name")
    private String fatherName;

    @Column(name = "mother_name")
    private String motherName;

    @Column(name = "takes_bus")
    private Boolean takesBus;

    @Column(name = "distance")
    private Double distance;

    @Column(name = "joining_date")
    private LocalDate joiningDate;

    @Column(name = "leaving_date")
    private LocalDate leavingDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private StudentStatus status;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Student() {
        this.takesBus = false;
        this.distance = 0.0;
    }

    public Student(String studentId, String name, String email, String phoneNumber, LocalDate dob, String className, String gender, String fatherName, String motherName, Boolean takesBus, Double distance, LocalDate joiningDate, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.studentId = studentId;
        this.name = name;
        this.email = email;
        this.phoneNumber = phoneNumber;
        this.dob = dob;
        this.className = className;
        this.gender = gender;
        this.fatherName = fatherName;
        this.motherName = motherName;
        this.takesBus = takesBus;
        this.distance = distance;
        this.joiningDate = joiningDate;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getStudentId() {
        return studentId;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public LocalDate getDob() {
        return dob;
    }

    public String getClassName() {
        return className;
    }

    public String getGender() {
        return gender;
    }

    public String getFatherName() {
        return fatherName;
    }

    public String getMotherName() {
        return motherName;
    }

    public Boolean getTakesBus() {
        return takesBus;
    }

    public Double getDistance() {
        return distance;
    }

    public LocalDate getJoiningDate() {
        return joiningDate;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setStudentId(String studentId) {
        this.studentId = studentId;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public void setDob(LocalDate dob) {
        this.dob = dob;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public void setFatherName(String fatherName) {
        this.fatherName = fatherName;
    }

    public void setMotherName(String motherName) {
        this.motherName = motherName;
    }

    public void setTakesBus(Boolean takesBus) {
        this.takesBus = takesBus;
    }

    public void setDistance(Double distance) {
        this.distance = distance;
    }

    public void setJoiningDate(LocalDate joiningDate) {
        this.joiningDate = joiningDate;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public LocalDate getLeavingDate() { return leavingDate; }

    public StudentStatus getStatus() { return status; }

    public void setLeavingDate(LocalDate leavingDate) { this.leavingDate = leavingDate; }

    public void setStatus(StudentStatus status) { this.status = status; }
}