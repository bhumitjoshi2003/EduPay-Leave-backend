package com.indraacademy.ias_management.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class PlanRequest {
    @NotBlank(message = "Plan name is required")
    @Size(max = 100, message = "Plan name must not exceed 100 characters")
    private String name;

    @NotBlank(message = "Tier is required")
    private String tier;

    @NotBlank(message = "Version is required")
    private String version;
    @JsonProperty("isPublic")
    private boolean isPublic = true;
    private Integer maxStudents;
    private Integer studentSoftLimitPct = 90;
    private Integer studentHardLimitPct = 105;
    private Integer maxStaff;
    private Integer staffSoftLimitPct = 90;
    private Integer staffHardLimitPct = 105;
    private Integer storageGbLimit;
    private Integer storageSoftLimitPct = 90;
    private Integer storageHardLimitPct = 105;
    private Long monthlyPricePaise;
    private Long annualPricePaise;
    private Integer priorityScore;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public boolean isPublic() { return isPublic; }
    public void setPublic(boolean isPublic) { this.isPublic = isPublic; }

    public Integer getMaxStudents() { return maxStudents; }
    public void setMaxStudents(Integer maxStudents) { this.maxStudents = maxStudents; }

    public Integer getStudentSoftLimitPct() { return studentSoftLimitPct; }
    public void setStudentSoftLimitPct(Integer v) { this.studentSoftLimitPct = v; }

    public Integer getStudentHardLimitPct() { return studentHardLimitPct; }
    public void setStudentHardLimitPct(Integer v) { this.studentHardLimitPct = v; }

    public Integer getMaxStaff() { return maxStaff; }
    public void setMaxStaff(Integer maxStaff) { this.maxStaff = maxStaff; }

    public Integer getStaffSoftLimitPct() { return staffSoftLimitPct; }
    public void setStaffSoftLimitPct(Integer v) { this.staffSoftLimitPct = v; }

    public Integer getStaffHardLimitPct() { return staffHardLimitPct; }
    public void setStaffHardLimitPct(Integer v) { this.staffHardLimitPct = v; }

    public Integer getStorageGbLimit() { return storageGbLimit; }
    public void setStorageGbLimit(Integer storageGbLimit) { this.storageGbLimit = storageGbLimit; }

    public Integer getStorageSoftLimitPct() { return storageSoftLimitPct; }
    public void setStorageSoftLimitPct(Integer v) { this.storageSoftLimitPct = v; }

    public Integer getStorageHardLimitPct() { return storageHardLimitPct; }
    public void setStorageHardLimitPct(Integer v) { this.storageHardLimitPct = v; }

    public Long getMonthlyPricePaise() { return monthlyPricePaise; }
    public void setMonthlyPricePaise(Long monthlyPricePaise) { this.monthlyPricePaise = monthlyPricePaise; }

    public Long getAnnualPricePaise() { return annualPricePaise; }
    public void setAnnualPricePaise(Long annualPricePaise) { this.annualPricePaise = annualPricePaise; }

    public Integer getPriorityScore() { return priorityScore; }
    public void setPriorityScore(Integer priorityScore) { this.priorityScore = priorityScore; }
}
