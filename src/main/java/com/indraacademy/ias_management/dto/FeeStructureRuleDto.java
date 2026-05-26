package com.indraacademy.ias_management.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public class FeeStructureRuleDto {
    private Long id;

    @NotNull
    private Long feeHeadId;

    private String feeHeadName;
    private String feeHeadCode;

    @NotNull
    private Long academicSessionId;

    @NotBlank
    private String className;

    @NotNull
    @Min(0)
    private Long amount;

    @NotNull
    private LocalDate effectiveFrom;

    private LocalDate effectiveUntil;

    public FeeStructureRuleDto() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getFeeHeadId() { return feeHeadId; }
    public void setFeeHeadId(Long feeHeadId) { this.feeHeadId = feeHeadId; }

    public String getFeeHeadName() { return feeHeadName; }
    public void setFeeHeadName(String feeHeadName) { this.feeHeadName = feeHeadName; }

    public String getFeeHeadCode() { return feeHeadCode; }
    public void setFeeHeadCode(String feeHeadCode) { this.feeHeadCode = feeHeadCode; }

    public Long getAcademicSessionId() { return academicSessionId; }
    public void setAcademicSessionId(Long academicSessionId) { this.academicSessionId = academicSessionId; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }

    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(LocalDate effectiveFrom) { this.effectiveFrom = effectiveFrom; }

    public LocalDate getEffectiveUntil() { return effectiveUntil; }
    public void setEffectiveUntil(LocalDate effectiveUntil) { this.effectiveUntil = effectiveUntil; }
}
