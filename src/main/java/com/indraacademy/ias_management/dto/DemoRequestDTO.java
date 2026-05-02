package com.indraacademy.ias_management.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class DemoRequestDTO {

    @NotBlank
    @Size(max = 255)
    private String schoolName;

    @NotBlank
    @Size(max = 255)
    private String contactName;

    @NotBlank
    @Email
    @Size(max = 255)
    private String email;

    @NotBlank
    @Size(max = 50)
    private String phone;

    @Size(max = 50)
    private String numberOfStudents;

    @Size(max = 150)
    private String city;

    private String message;

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
}
