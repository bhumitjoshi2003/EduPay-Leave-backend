package com.indraacademy.ias_management.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public class StudentFeeConfigDto {
    private Long id;

    @NotBlank
    private String studentId;

    @NotNull
    private Long feeHeadId;

    private String feeHeadName;

    @NotNull
    private Long academicSessionId;

    @NotNull
    private String configType;

    private BigDecimal value;
    private String reason;
    private LocalDate validFrom;
    private LocalDate validUntil;

    public StudentFeeConfigDto() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }

    public Long getFeeHeadId() { return feeHeadId; }
    public void setFeeHeadId(Long feeHeadId) { this.feeHeadId = feeHeadId; }

    public String getFeeHeadName() { return feeHeadName; }
    public void setFeeHeadName(String feeHeadName) { this.feeHeadName = feeHeadName; }

    public Long getAcademicSessionId() { return academicSessionId; }
    public void setAcademicSessionId(Long academicSessionId) { this.academicSessionId = academicSessionId; }

    public String getConfigType() { return configType; }
    public void setConfigType(String configType) { this.configType = configType; }

    public BigDecimal getValue() { return value; }
    public void setValue(BigDecimal value) { this.value = value; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public LocalDate getValidFrom() { return validFrom; }
    public void setValidFrom(LocalDate validFrom) { this.validFrom = validFrom; }

    public LocalDate getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDate validUntil) { this.validUntil = validUntil; }
}
