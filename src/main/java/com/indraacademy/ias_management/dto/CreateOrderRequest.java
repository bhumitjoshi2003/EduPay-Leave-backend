package com.indraacademy.ias_management.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class CreateOrderRequest {
    @NotNull @Min(1)
    private Integer totalAmount;

    @NotBlank
    private String studentId;

    @NotBlank
    private String studentName;

    @NotBlank
    private String className;

    @NotBlank
    private String session;

    @NotBlank
    private String monthSelectionString;

    @NotNull
    private Integer totalBusFee;

    @NotNull
    private Integer totalTuitionFee;

    @NotNull
    private Integer totalAnnualCharges;

    @NotNull
    private Integer totalLabCharges;

    @NotNull
    private Integer totalEcaProject;

    @NotNull
    private Integer totalExaminationFee;

    private Integer additionalCharges;

    private Integer lateFees;

    private Integer platformFee;

    // Getters and setters
    public Integer getTotalAmount() { return totalAmount; }
    public void setTotalAmount(Integer totalAmount) { this.totalAmount = totalAmount; }

    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }

    public String getStudentName() { return studentName; }
    public void setStudentName(String studentName) { this.studentName = studentName; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public String getSession() { return session; }
    public void setSession(String session) { this.session = session; }

    public String getMonthSelectionString() { return monthSelectionString; }
    public void setMonthSelectionString(String monthSelectionString) { this.monthSelectionString = monthSelectionString; }

    public Integer getTotalBusFee() { return totalBusFee; }
    public void setTotalBusFee(Integer totalBusFee) { this.totalBusFee = totalBusFee; }

    public Integer getTotalTuitionFee() { return totalTuitionFee; }
    public void setTotalTuitionFee(Integer totalTuitionFee) { this.totalTuitionFee = totalTuitionFee; }

    public Integer getTotalAnnualCharges() { return totalAnnualCharges; }
    public void setTotalAnnualCharges(Integer totalAnnualCharges) { this.totalAnnualCharges = totalAnnualCharges; }

    public Integer getTotalLabCharges() { return totalLabCharges; }
    public void setTotalLabCharges(Integer totalLabCharges) { this.totalLabCharges = totalLabCharges; }

    public Integer getTotalEcaProject() { return totalEcaProject; }
    public void setTotalEcaProject(Integer totalEcaProject) { this.totalEcaProject = totalEcaProject; }

    public Integer getTotalExaminationFee() { return totalExaminationFee; }
    public void setTotalExaminationFee(Integer totalExaminationFee) { this.totalExaminationFee = totalExaminationFee; }

    public Integer getAdditionalCharges() { return additionalCharges != null ? additionalCharges : 0; }
    public void setAdditionalCharges(Integer additionalCharges) { this.additionalCharges = additionalCharges; }

    public Integer getLateFees() { return lateFees != null ? lateFees : 0; }
    public void setLateFees(Integer lateFees) { this.lateFees = lateFees; }

    public Integer getPlatformFee() { return platformFee != null ? platformFee : 0; }
    public void setPlatformFee(Integer platformFee) { this.platformFee = platformFee; }
}
