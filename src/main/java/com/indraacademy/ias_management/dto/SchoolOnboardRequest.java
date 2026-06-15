package com.indraacademy.ias_management.dto;

import com.indraacademy.ias_management.entity.BoardType;
import com.indraacademy.ias_management.entity.SubscriptionPlan;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Request body for SUPER_ADMIN to onboard a new school.
 * Also creates the school's first ADMIN user account.
 */
public class SchoolOnboardRequest {

    // School fields
    @NotBlank(message = "School name is required")
    @Size(max = 255, message = "School name must not exceed 255 characters")
    private String name;

    @NotBlank(message = "Slug is required")
    @Size(max = 100, message = "Slug must not exceed 100 characters")
    private String slug;

    private BoardType boardType;

    @Size(max = 500, message = "Address must not exceed 500 characters")
    private String address;

    @Size(max = 100, message = "City must not exceed 100 characters")
    private String city;

    @Size(max = 100, message = "State must not exceed 100 characters")
    private String state;

    @Size(max = 10, message = "Pincode must not exceed 10 characters")
    private String pincode;

    @Email(message = "Invalid school email address")
    @Size(max = 255, message = "Email must not exceed 255 characters")
    private String email;

    @Size(max = 20, message = "Phone must not exceed 20 characters")
    private String phone;

    @Size(max = 255, message = "Website must not exceed 255 characters")
    private String website;

    private String logoUrl;
    private String themeColor;

    @Size(max = 255, message = "Contact person name must not exceed 255 characters")
    private String contactPersonName;

    private SubscriptionPlan plan;
    private Integer maxStudents;
    private LocalDate expiryDate;

    /** Optional: if set, a TRIAL subscription is auto-created using this plan + global defaultTrialDays. */
    private Long trialPlanId;

    /** Optional: custom trial end date. If null, falls back to now + globalConfig.defaultTrialDays. */
    private LocalDate trialEndsAt;

    // Academic configuration (defaults applied in entity if not provided)
    /** Calendar month the academic year starts (1=Jan ... 12=Dec). Defaults to 4 (April). */
    private Integer academicYearStartMonth;

    /** Comma-separated working day names, e.g. "MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY". */
    private String workingDays;

    /** Grading system: CBSE, LETTER, or PERCENTAGE. Defaults to CBSE. */
    private String gradingSystem;

    // First ADMIN user
    @NotBlank(message = "Admin user ID is required")
    @Size(max = 100, message = "Admin user ID must not exceed 100 characters")
    private String adminUserId;

    @NotBlank(message = "Admin email is required")
    @Email(message = "Invalid admin email address")
    @Size(max = 255, message = "Admin email must not exceed 255 characters")
    private String adminEmail;

    @NotBlank(message = "Admin password is required")
    @Size(min = 8, max = 255, message = "Admin password must be between 8 and 255 characters")
    private String adminPassword;

    @NotBlank(message = "Admin name is required")
    @Size(max = 255, message = "Admin name must not exceed 255 characters")
    private String adminName;

    @Size(max = 20, message = "Admin phone must not exceed 20 characters")
    private String adminPhone;

    @NotNull(message = "Admin date of birth is required")
    private LocalDate adminDob;

    @NotBlank(message = "Admin gender is required")
    private String adminGender;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

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

    public String getLogoUrl() { return logoUrl; }
    public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }

    public String getThemeColor() { return themeColor; }
    public void setThemeColor(String themeColor) { this.themeColor = themeColor; }

    public String getContactPersonName() { return contactPersonName; }
    public void setContactPersonName(String contactPersonName) { this.contactPersonName = contactPersonName; }

    public SubscriptionPlan getPlan() { return plan; }
    public void setPlan(SubscriptionPlan plan) { this.plan = plan; }

    public Integer getMaxStudents() { return maxStudents; }
    public void setMaxStudents(Integer maxStudents) { this.maxStudents = maxStudents; }

    public LocalDate getExpiryDate() { return expiryDate; }
    public void setExpiryDate(LocalDate expiryDate) { this.expiryDate = expiryDate; }

    public String getAdminUserId() { return adminUserId; }
    public void setAdminUserId(String adminUserId) { this.adminUserId = adminUserId; }

    public String getAdminEmail() { return adminEmail; }
    public void setAdminEmail(String adminEmail) { this.adminEmail = adminEmail; }

    public String getAdminPassword() { return adminPassword; }
    public void setAdminPassword(String adminPassword) { this.adminPassword = adminPassword; }

    public String getAdminName() { return adminName; }
    public void setAdminName(String adminName) { this.adminName = adminName; }

    public String getAdminPhone() { return adminPhone; }
    public void setAdminPhone(String adminPhone) { this.adminPhone = adminPhone; }

    public LocalDate getAdminDob() { return adminDob; }
    public void setAdminDob(LocalDate adminDob) { this.adminDob = adminDob; }

    public String getAdminGender() { return adminGender; }
    public void setAdminGender(String adminGender) { this.adminGender = adminGender; }

    public Long getTrialPlanId() { return trialPlanId; }
    public void setTrialPlanId(Long trialPlanId) { this.trialPlanId = trialPlanId; }

    public LocalDate getTrialEndsAt() { return trialEndsAt; }
    public void setTrialEndsAt(LocalDate trialEndsAt) { this.trialEndsAt = trialEndsAt; }

    public Integer getAcademicYearStartMonth() { return academicYearStartMonth; }
    public void setAcademicYearStartMonth(Integer academicYearStartMonth) { this.academicYearStartMonth = academicYearStartMonth; }

    public String getWorkingDays() { return workingDays; }
    public void setWorkingDays(String workingDays) { this.workingDays = workingDays; }

    public String getGradingSystem() { return gradingSystem; }
    public void setGradingSystem(String gradingSystem) { this.gradingSystem = gradingSystem; }
}
