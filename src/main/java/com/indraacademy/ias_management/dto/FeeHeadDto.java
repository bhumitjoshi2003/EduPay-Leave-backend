package com.indraacademy.ias_management.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public class FeeHeadDto {
    private Long id;

    @NotBlank
    @Size(max = 100)
    private String name;

    @NotBlank
    @Size(max = 30)
    private String code;

    @NotNull
    private String frequency;

    @NotNull
    private String dueMonths;

    private boolean optional;
    private boolean refundable;
    private BigDecimal siblingDiscountPct;
    private int displayOrder;
    private boolean active = true;

    public FeeHeadDto() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getFrequency() { return frequency; }
    public void setFrequency(String frequency) { this.frequency = frequency; }

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
}
