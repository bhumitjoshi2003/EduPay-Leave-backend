package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "plans")
public class Plan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** CAMPUS, ACADEMY, INSTITUTE, CUSTOM */
    @Column(nullable = false, length = 50)
    private String tier;

    /** Human-readable version label, e.g. "v1", "v2". Helps track pricing/feature evolution. */
    @Column(length = 20)
    private String version = "v1";

    /** false for hidden custom/enterprise plans not shown on pricing page */
    @Column(name = "is_public", nullable = false)
    private boolean isPublic = true;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    /**
     * Used to resolve which plan wins when a school has overlapping grants.
     * Campus=10, Academy=20, Institute=30, Custom plans=100+
     */
    @Column(name = "priority_score", nullable = false)
    private int priorityScore;

    // ── Student limits ────────────────────────────────────────────────────────
    @Column(name = "max_students")
    private Integer maxStudents;

    /** Percentage of maxStudents at which a soft warning is shown (e.g. 90) */
    @Column(name = "student_soft_limit_pct")
    private Integer studentSoftLimitPct = 90;

    /** Percentage of maxStudents at which further adds are hard-blocked (e.g. 105) */
    @Column(name = "student_hard_limit_pct")
    private Integer studentHardLimitPct = 105;

    // ── Staff limits ──────────────────────────────────────────────────────────
    @Column(name = "max_staff")
    private Integer maxStaff;

    @Column(name = "staff_soft_limit_pct")
    private Integer staffSoftLimitPct = 90;

    @Column(name = "staff_hard_limit_pct")
    private Integer staffHardLimitPct = 105;

    // ── Storage limits ────────────────────────────────────────────────────────
    @Column(name = "storage_gb_limit")
    private Integer storageGbLimit;

    @Column(name = "storage_soft_limit_pct")
    private Integer storageSoftLimitPct = 90;

    @Column(name = "storage_hard_limit_pct")
    private Integer storageHardLimitPct = 105;

    // ── Pricing (null = offline/custom negotiated) ────────────────────────────
    @Column(name = "monthly_price_paise")
    private Long monthlyPricePaise;

    @Column(name = "annual_price_paise")
    private Long annualPricePaise;

    @Column(length = 10, nullable = false)
    private String currency = "INR";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Plan() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public boolean isPublic() { return isPublic; }
    public void setPublic(boolean isPublic) { this.isPublic = isPublic; }

    public boolean isActive() { return isActive; }
    public void setActive(boolean isActive) { this.isActive = isActive; }

    public int getPriorityScore() { return priorityScore; }
    public void setPriorityScore(int priorityScore) { this.priorityScore = priorityScore; }

    public Integer getMaxStudents() { return maxStudents; }
    public void setMaxStudents(Integer maxStudents) { this.maxStudents = maxStudents; }

    public Integer getStudentSoftLimitPct() { return studentSoftLimitPct; }
    public void setStudentSoftLimitPct(Integer studentSoftLimitPct) { this.studentSoftLimitPct = studentSoftLimitPct; }

    public Integer getStudentHardLimitPct() { return studentHardLimitPct; }
    public void setStudentHardLimitPct(Integer studentHardLimitPct) { this.studentHardLimitPct = studentHardLimitPct; }

    public Integer getMaxStaff() { return maxStaff; }
    public void setMaxStaff(Integer maxStaff) { this.maxStaff = maxStaff; }

    public Integer getStaffSoftLimitPct() { return staffSoftLimitPct; }
    public void setStaffSoftLimitPct(Integer staffSoftLimitPct) { this.staffSoftLimitPct = staffSoftLimitPct; }

    public Integer getStaffHardLimitPct() { return staffHardLimitPct; }
    public void setStaffHardLimitPct(Integer staffHardLimitPct) { this.staffHardLimitPct = staffHardLimitPct; }

    public Integer getStorageGbLimit() { return storageGbLimit; }
    public void setStorageGbLimit(Integer storageGbLimit) { this.storageGbLimit = storageGbLimit; }

    public Integer getStorageSoftLimitPct() { return storageSoftLimitPct; }
    public void setStorageSoftLimitPct(Integer storageSoftLimitPct) { this.storageSoftLimitPct = storageSoftLimitPct; }

    public Integer getStorageHardLimitPct() { return storageHardLimitPct; }
    public void setStorageHardLimitPct(Integer storageHardLimitPct) { this.storageHardLimitPct = storageHardLimitPct; }

    public Long getMonthlyPricePaise() { return monthlyPricePaise; }
    public void setMonthlyPricePaise(Long monthlyPricePaise) { this.monthlyPricePaise = monthlyPricePaise; }

    public Long getAnnualPricePaise() { return annualPricePaise; }
    public void setAnnualPricePaise(Long annualPricePaise) { this.annualPricePaise = annualPricePaise; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
