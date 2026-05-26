package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "fee_head",
        uniqueConstraints = @UniqueConstraint(name = "uq_fee_head_school_code",
                columnNames = {"school_id", "code"}))
public class FeeHead {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** Unique code within a school, e.g. TUITION, BUS, ANNUAL, LAB, ECA, EXAM */
    @Column(name = "code", nullable = false, length = 30)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", nullable = false, length = 20)
    private FeeFrequency frequency;

    /**
     * JSON array of academic months (1-12) when this fee is due.
     * e.g. "[1,2,3,4,5,6,7,8,9,10,11,12]" for monthly,
     *      "[1]" for annual (charged in first month only).
     */
    @Column(name = "due_months", nullable = false, columnDefinition = "TEXT")
    private String dueMonths;

    @Column(name = "is_optional", nullable = false)
    private boolean optional = false;

    @Column(name = "is_refundable", nullable = false)
    private boolean refundable = false;

    /** Sibling discount percentage (0-100). Applied when multiple siblings enrolled. */
    @Column(name = "sibling_discount_pct", precision = 5, scale = 2)
    private BigDecimal siblingDiscountPct;

    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public FeeHead() {}

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public FeeFrequency getFrequency() { return frequency; }
    public void setFrequency(FeeFrequency frequency) { this.frequency = frequency; }

    public String getDueMonths() { return dueMonths; }
    public void setDueMonths(String dueMonths) { this.dueMonths = dueMonths; }

    public boolean isOptional() { return optional; }
    public void setOptional(boolean optional) { this.optional = optional; }

    public boolean isRefundable() { return refundable; }
    public void setRefundable(boolean refundable) { this.refundable = refundable; }

    public BigDecimal getSiblingDiscountPct() { return siblingDiscountPct; }
    public void setSiblingDiscountPct(BigDecimal siblingDiscountPct) { this.siblingDiscountPct = siblingDiscountPct; }

    public int getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(int displayOrder) { this.displayOrder = displayOrder; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
