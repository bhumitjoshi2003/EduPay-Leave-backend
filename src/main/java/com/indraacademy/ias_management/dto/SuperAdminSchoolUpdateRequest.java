package com.indraacademy.ias_management.dto;

import com.indraacademy.ias_management.entity.BoardType;
import com.indraacademy.ias_management.entity.SubscriptionPlan;

import java.time.LocalDate;

/**
 * Request body for SUPER_ADMIN to update all fields of an existing school.
 */
public class SuperAdminSchoolUpdateRequest {

    // School info
    private String name;
    private BoardType boardType;
    private String address;
    private String city;
    private String state;
    private String pincode;
    private String email;
    private String phone;
    private String website;
    private String contactPersonName;

    // Subscription
    private SubscriptionPlan plan;
    private Integer maxStudents;
    private LocalDate expiryDate;
    private Boolean active;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public BoardType getBoardType() { return boardType; }
    public void setBoardType(BoardType boardType) { this.boardType = boardType; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getPincode() { return pincode; }
    public void setPincode(String pincode) { this.pincode = pincode; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getWebsite() { return website; }
    public void setWebsite(String website) { this.website = website; }

    public String getContactPersonName() { return contactPersonName; }
    public void setContactPersonName(String contactPersonName) { this.contactPersonName = contactPersonName; }

    public SubscriptionPlan getPlan() { return plan; }
    public void setPlan(SubscriptionPlan plan) { this.plan = plan; }

    public Integer getMaxStudents() { return maxStudents; }
    public void setMaxStudents(Integer maxStudents) { this.maxStudents = maxStudents; }

    public LocalDate getExpiryDate() { return expiryDate; }
    public void setExpiryDate(LocalDate expiryDate) { this.expiryDate = expiryDate; }

    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
}
