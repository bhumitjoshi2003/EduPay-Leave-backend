package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "demo_requests")
public class DemoRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "school_name", nullable = false, length = 255)
    private String schoolName;

    @Column(name = "contact_name", nullable = false, length = 255)
    private String contactName;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "phone", nullable = false, length = 50)
    private String phone;

    @Column(name = "number_of_students", length = 50)
    private String numberOfStudents;

    @Column(name = "city", length = 150)
    private String city;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "VARCHAR(20)")
    private DemoRequestStatus status = DemoRequestStatus.PENDING;

    @CreationTimestamp
    @Column(name = "requested_at", nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    public DemoRequest() {}

    public Long getId() { return id; }

    public String getSchoolName() { return schoolName; }
    public void setSchoolName(String schoolName) { this.schoolName = schoolName; }

    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getNumberOfStudents() { return numberOfStudents; }
    public void setNumberOfStudents(String numberOfStudents) { this.numberOfStudents = numberOfStudents; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public DemoRequestStatus getStatus() { return status; }
    public void setStatus(DemoRequestStatus status) { this.status = status; }

    public LocalDateTime getRequestedAt() { return requestedAt; }
    public void setRequestedAt(LocalDateTime requestedAt) { this.requestedAt = requestedAt; }
}
