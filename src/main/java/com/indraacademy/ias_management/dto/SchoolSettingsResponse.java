package com.indraacademy.ias_management.dto;

import com.indraacademy.ias_management.entity.BoardType;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.SubscriptionPlan;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * School settings returned to the client.
 * Razorpay secrets are intentionally excluded; only the key ID is exposed
 * so the frontend can show whether Razorpay is configured.
 */
public class SchoolSettingsResponse {

    private Long id;
    private String name;
    private String slug;
    private BoardType boardType;
    private String address;
    private String city;
    private String state;
    private String pincode;
    private String email;
    private String phone;
    private String website;
    private String logoUrl;
    private String themeColor;
    private String contactPersonName;
    private SubscriptionPlan plan;
    private Integer maxStudents;
    private LocalDate expiryDate;
    private boolean active;
    private LocalDateTime createdAt;
    private String onboardedBy;
    /** Exposed so frontend knows if Razorpay is configured; secret is never sent. */
    private boolean razorpayConfigured;
    /** userId of the school's ADMIN account — populated by service layer when available. */
    private String adminUserId;

    public static SchoolSettingsResponse from(School school) {
        SchoolSettingsResponse r = new SchoolSettingsResponse();
        r.id = school.getId();
        r.name = school.getName();
        r.slug = school.getSlug();
        r.boardType = school.getBoardType();
        r.address = school.getAddress();
        r.city = school.getCity();
        r.state = school.getState();
        r.pincode = school.getPincode();
        r.email = school.getEmail();
        r.phone = school.getPhone();
        r.website = school.getWebsite();
        r.logoUrl = school.getLogoUrl();
        r.themeColor = school.getThemeColor();
        r.contactPersonName = school.getContactPersonName();
        r.plan = school.getPlan();
        r.maxStudents = school.getMaxStudents();
        r.expiryDate = school.getExpiryDate();
        r.active = school.isActive();
        r.createdAt = school.getCreatedAt();
        r.onboardedBy = school.getOnboardedBy();
        r.razorpayConfigured = school.getRazorpayKeyId() != null && !school.getRazorpayKeyId().isBlank();
        return r;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public BoardType getBoardType() { return boardType; }
    public String getAddress() { return address; }
    public String getCity() { return city; }
    public String getState() { return state; }
    public String getPincode() { return pincode; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public String getWebsite() { return website; }
    public String getLogoUrl() { return logoUrl; }
    public String getThemeColor() { return themeColor; }
    public String getContactPersonName() { return contactPersonName; }
    public SubscriptionPlan getPlan() { return plan; }
    public Integer getMaxStudents() { return maxStudents; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public boolean isActive() { return active; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public String getOnboardedBy() { return onboardedBy; }
    public boolean isRazorpayConfigured() { return razorpayConfigured; }
    public String getAdminUserId() { return adminUserId; }
    public void setAdminUserId(String adminUserId) { this.adminUserId = adminUserId; }
}
