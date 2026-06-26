package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "school",
        uniqueConstraints = @UniqueConstraint(name = "uq_school_slug", columnNames = "slug"))
public class School {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(name = "board_type", length = 20)
    private BoardType boardType;

    @Column(columnDefinition = "TEXT")
    private String address;

    @Column(length = 100)
    private String city;

    @Column(length = 100)
    private String state;

    @Column(length = 10)
    private String pincode;

    @Column(name = "logo_url")
    private String logoUrl;

    @Column(name = "theme_color", length = 20)
    private String themeColor;

    @Column(name = "contact_person_name")
    private String contactPersonName;

    @Column(length = 255)
    private String email;

    @Column(length = 20)
    private String phone;

    @Column(length = 255)
    private String website;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan", nullable = false, length = 20)
    private SubscriptionPlan plan = SubscriptionPlan.TRIAL;

    @Column(name = "max_students")
    private Integer maxStudents;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "active", nullable = false)
    private boolean active = false;

    @Column(name = "razorpay_key_id")
    private String razorpayKeyId;

    @Column(name = "razorpay_key_secret")
    private String razorpayKeySecret;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "onboarded_by")
    private String onboardedBy;

    /**
     * First calendar month of the academic year (1=Jan, 4=Apr, 7=Jul, etc.).
     * Default: 4 (April) — the standard Indian academic year start.
     */
    @Column(name = "academic_year_start_month", nullable = false)
    private int academicYearStartMonth = 4;

    /**
     * Comma-separated working days, e.g. "MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY".
     * Used for timetable and attendance trend calculations.
     */
    @Column(name = "working_days", length = 100)
    private String workingDays = "MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY";

    /**
     * Number of periods per school day shown in the timetable UI.
     * Default: 8.
     */
    @Column(name = "periods_per_day", nullable = false)
    private int periodsPerDay = 8;

    /**
     * Grading system used in report cards.
     * Supported values: CBSE, PERCENTAGE, LETTER.
     * Default: CBSE.
     */
    @Column(name = "grading_system", length = 20)
    private String gradingSystem = "CBSE";

    /** Board affiliation number — e.g. CBSE school number, ICSE index number. */
    @Column(name = "affiliation_number", length = 100)
    private String affiliationNumber;

    /** School identification code — e.g. government registration code or board-assigned code. */
    @Column(name = "school_code", length = 50)
    private String schoolCode;

    /** Custom report card header image — when set, replaces the auto-generated header in PDFs and web view. */
    @Column(name = "report_card_header_image_url", length = 500)
    private String reportCardHeaderImageUrl;

    // ── Staff attendance / GPS check-in settings ──

    @Column(name = "school_latitude")
    private Double schoolLatitude;

    @Column(name = "school_longitude")
    private Double schoolLongitude;

    @Column(name = "geofence_radius")
    private Integer geofenceRadius = 200;

    @Column(name = "school_start_time")
    private LocalTime schoolStartTime;

    @Column(name = "late_threshold_minutes")
    private Integer lateThresholdMinutes = 5;

    @Column(name = "checkin_window_start")
    private LocalTime checkinWindowStart;

    @Column(name = "checkin_window_end")
    private LocalTime checkinWindowEnd;

    public School() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

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

    public String getLogoUrl() { return logoUrl; }
    public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }

    public String getThemeColor() { return themeColor; }
    public void setThemeColor(String themeColor) { this.themeColor = themeColor; }

    public String getContactPersonName() { return contactPersonName; }
    public void setContactPersonName(String contactPersonName) { this.contactPersonName = contactPersonName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getWebsite() { return website; }
    public void setWebsite(String website) { this.website = website; }

    public SubscriptionPlan getPlan() { return plan; }
    public void setPlan(SubscriptionPlan plan) { this.plan = plan; }

    public Integer getMaxStudents() { return maxStudents; }
    public void setMaxStudents(Integer maxStudents) { this.maxStudents = maxStudents; }

    public LocalDate getExpiryDate() { return expiryDate; }
    public void setExpiryDate(LocalDate expiryDate) { this.expiryDate = expiryDate; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public String getRazorpayKeyId() { return razorpayKeyId; }
    public void setRazorpayKeyId(String razorpayKeyId) { this.razorpayKeyId = razorpayKeyId; }

    public String getRazorpayKeySecret() { return razorpayKeySecret; }
    public void setRazorpayKeySecret(String razorpayKeySecret) { this.razorpayKeySecret = razorpayKeySecret; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getOnboardedBy() { return onboardedBy; }
    public void setOnboardedBy(String onboardedBy) { this.onboardedBy = onboardedBy; }

    public int getAcademicYearStartMonth() { return academicYearStartMonth; }
    public void setAcademicYearStartMonth(int academicYearStartMonth) { this.academicYearStartMonth = academicYearStartMonth; }

    public String getWorkingDays() { return workingDays; }
    public void setWorkingDays(String workingDays) { this.workingDays = workingDays; }

    public int getPeriodsPerDay() { return periodsPerDay; }
    public void setPeriodsPerDay(int periodsPerDay) { this.periodsPerDay = periodsPerDay; }

    public String getGradingSystem() { return gradingSystem; }
    public void setGradingSystem(String gradingSystem) { this.gradingSystem = gradingSystem; }

    public String getAffiliationNumber() { return affiliationNumber; }
    public void setAffiliationNumber(String affiliationNumber) { this.affiliationNumber = affiliationNumber; }

    public String getSchoolCode() { return schoolCode; }
    public void setSchoolCode(String schoolCode) { this.schoolCode = schoolCode; }

    public String getReportCardHeaderImageUrl() { return reportCardHeaderImageUrl; }
    public void setReportCardHeaderImageUrl(String reportCardHeaderImageUrl) { this.reportCardHeaderImageUrl = reportCardHeaderImageUrl; }

    public Double getSchoolLatitude() { return schoolLatitude; }
    public void setSchoolLatitude(Double schoolLatitude) { this.schoolLatitude = schoolLatitude; }

    public Double getSchoolLongitude() { return schoolLongitude; }
    public void setSchoolLongitude(Double schoolLongitude) { this.schoolLongitude = schoolLongitude; }

    public Integer getGeofenceRadius() { return geofenceRadius; }
    public void setGeofenceRadius(Integer geofenceRadius) { this.geofenceRadius = geofenceRadius; }

    public LocalTime getSchoolStartTime() { return schoolStartTime; }
    public void setSchoolStartTime(LocalTime schoolStartTime) { this.schoolStartTime = schoolStartTime; }

    public Integer getLateThresholdMinutes() { return lateThresholdMinutes; }
    public void setLateThresholdMinutes(Integer lateThresholdMinutes) { this.lateThresholdMinutes = lateThresholdMinutes; }

    public LocalTime getCheckinWindowStart() { return checkinWindowStart; }
    public void setCheckinWindowStart(LocalTime checkinWindowStart) { this.checkinWindowStart = checkinWindowStart; }

    public LocalTime getCheckinWindowEnd() { return checkinWindowEnd; }
    public void setCheckinWindowEnd(LocalTime checkinWindowEnd) { this.checkinWindowEnd = checkinWindowEnd; }
}
