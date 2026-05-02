package com.indraacademy.ias_management.dto;

import com.indraacademy.ias_management.entity.DemoRequestStatus;

import java.time.LocalDateTime;

public class DemoRequestResponseDTO {

    private Long id;
    private String schoolName;
    private String contactName;
    private String email;
    private String phone;
    private String numberOfStudents;
    private String city;
    private String message;
    private DemoRequestStatus status;
    private LocalDateTime requestedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

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
