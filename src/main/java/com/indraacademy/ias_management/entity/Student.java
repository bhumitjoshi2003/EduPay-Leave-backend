package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.data.domain.Persistable;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Implements Persistable&lt;String&gt; so save() correctly INSERTs new students and
 * UPDATEs existing ones. Without this, since studentId is an assigned (non-generated)
 * @Id, Spring Data's default isNew() check ("is the id null?") is always false — save()
 * would ALWAYS call entityManager.merge(), never persist(). merge() silently upserts:
 * if a row with that studentId already exists under a DIFFERENT school, merge() just
 * overwrites it in place (reassigning it to the new school) instead of failing — this
 * was a real cross-tenant data-corruption bug, not merely a missing validation.
 */
@Entity
@Table(name = "student", indexes = {
    @Index(name = "idx_student_class_status", columnList = "class_name, status")
})
@Data
public class Student implements Persistable<String> {

    @Column(name = "school_id")
    private Long schoolId;

    @Id
    @Column(name = "student_id", length = 50)
    private String studentId;

    @Column(name = "name", length = 200)
    private String name;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Column(name = "dob")
    private LocalDate dob;

    @Column(name = "class_name", length = 50)
    private String className;

    @Column(name = "class_id")
    private Long classId;

    @Column(name = "section_id")
    private Long sectionId;

    @Column(name = "section_name", length = 50)
    private String sectionName;

    @Column(name = "gender", length = 10)
    private String gender;

    @Column(name = "father_name", length = 200)
    private String fatherName;

    @Column(name = "mother_name", length = 200)
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

    @Column(name = "photo_url")
    private String photoUrl;

    @Column(name = "reason_for_leaving", length = 500)
    private String reasonForLeaving;

    @Column(name = "conduct_at_leaving", length = 100)
    private String conductAtLeaving;

    @Column(name = "exit_remarks", length = 1000)
    private String exitRemarks;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Defaults true: a freshly-constructed instance (registration form, CSV row) is new
    // until proven otherwise. @PostLoad flips it false for anything JPA actually loaded
    // from the DB. Excluded from equals/hashCode/toString — it's lifecycle bookkeeping,
    // not part of the entity's identity or data.
    @Transient
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private boolean isNew = true;

    @Override
    public String getId() {
        return studentId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    /**
     * Call before save() when this instance was constructed fresh (e.g. deserialized
     * from a request body) but is known to represent an update of a row that already
     * exists — without this, save() would try to INSERT it and fail. See
     * StudentService.updateStudent(), the one call site that needs this explicitly.
     */
    public void markAsExisting() {
        this.isNew = false;
    }

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

    public String getPhotoUrl() { return photoUrl; }

    public void setPhotoUrl(String photoUrl) { this.photoUrl = photoUrl; }

    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }

    public String getSectionName() { return sectionName; }
    public void setSectionName(String sectionName) { this.sectionName = sectionName; }

    public String getReasonForLeaving() { return reasonForLeaving; }
    public void setReasonForLeaving(String reasonForLeaving) { this.reasonForLeaving = reasonForLeaving; }

    public String getConductAtLeaving() { return conductAtLeaving; }
    public void setConductAtLeaving(String conductAtLeaving) { this.conductAtLeaving = conductAtLeaving; }

    public String getExitRemarks() { return exitRemarks; }
    public void setExitRemarks(String exitRemarks) { this.exitRemarks = exitRemarks; }
}