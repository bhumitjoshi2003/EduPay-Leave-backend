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

/** See Student.java's class Javadoc — same Persistable<String> rationale applies here:
 * adminId is an assigned @Id, so save() would otherwise always merge()/upsert instead of
 * correctly INSERTing new admins or rejecting an adminId collision. AdminService.createAdmin()
 * previously had no duplicate pre-check at all, so this was this entity's only protection. */
@Entity
@Table(name = "admin") // Changed table name to "admin"
@Data
public class Admin implements Persistable<String> {

    @Column(name = "school_id")
    private Long schoolId;

    @Id
    @Column(name = "admin_id", nullable = false)
    private String adminId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "phone_number", nullable = false)
    private String phoneNumber;

    @Column(name = "dob", nullable = false)
    private LocalDate dob;

    @Column(name = "gender")
    private String gender;

    @Column(name = "photo_url")
    private String photoUrl;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Transient
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private boolean isNew = true;

    @Override
    public String getId() {
        return adminId;
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

    /** See Student.markAsExisting() — same purpose, kept for consistency even though
     * AdminService.updateAdmin() currently always mutates a JPA-loaded instance (so
     * @PostLoad alone already covers it) rather than a fresh request-body object. */
    public void markAsExisting() {
        this.isNew = false;
    }

    // Constructors, getters, and setters (using Lombok's @Data)
    public Admin() {
    }

    public String getAdminId() {
        return adminId;
    }

    public void setAdminId(String adminId) {
        this.adminId = adminId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public LocalDate getDob() {
        return dob;
    }

    public void setDob(LocalDate dob) {
        this.dob = dob;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public String getPhotoUrl() { return photoUrl; }

    public void setPhotoUrl(String photoUrl) { this.photoUrl = photoUrl; }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }
}

