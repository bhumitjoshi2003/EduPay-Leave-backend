package com.indraacademy.ias_management.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public class InvoiceGenerationRequest {

    @NotNull
    private Long academicSessionId;

    @NotNull
    @Min(1)
    @Max(12)
    private Integer billingMonth;

    /** Optional: generate for a specific class only. Null = all classes. */
    private String className;

    /** Optional: generate for a specific student only. Null = all students. */
    private String studentId;

    public InvoiceGenerationRequest() {}

    public Long getAcademicSessionId() { return academicSessionId; }
    public void setAcademicSessionId(Long academicSessionId) { this.academicSessionId = academicSessionId; }

    public Integer getBillingMonth() { return billingMonth; }
    public void setBillingMonth(Integer billingMonth) { this.billingMonth = billingMonth; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }
}
