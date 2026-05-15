package com.indraacademy.ias_management.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.indraacademy.ias_management.entity.FeatureCatalog;
import com.indraacademy.ias_management.entity.Plan;

import java.time.LocalDateTime;
import java.util.List;

public class PlanResponse {
    private Long id;
    private String name;
    private String tier;
    private String version;
    @JsonProperty("isPublic")
    private boolean isPublic;
    @JsonProperty("isActive")
    private boolean isActive;
    private int priorityScore;

    private Integer maxStudents;
    private Integer studentSoftLimitPct;
    private Integer studentHardLimitPct;

    private Integer maxStaff;
    private Integer staffSoftLimitPct;
    private Integer staffHardLimitPct;

    private Integer storageGbLimit;
    private Integer storageSoftLimitPct;
    private Integer storageHardLimitPct;

    private Long monthlyPricePaise;
    private Long annualPricePaise;
    private String currency;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** Current active features on this plan */
    private List<FeatureCatalogResponse> features;

    /** Pending (unapplied) changes — lets the UI warn "X will be removed on date" */
    private List<PlanFeatureChangeResponse> pendingChanges;

    public static PlanResponse from(Plan p, List<FeatureCatalog> features,
                                    List<PlanFeatureChangeResponse> pendingChanges) {
        PlanResponse r = new PlanResponse();
        r.id                   = p.getId();
        r.name                 = p.getName();
        r.tier                 = p.getTier();
        r.version              = p.getVersion();
        r.isPublic             = p.isPublic();
        r.isActive             = p.isActive();
        r.priorityScore        = p.getPriorityScore();
        r.maxStudents          = p.getMaxStudents();
        r.studentSoftLimitPct  = p.getStudentSoftLimitPct();
        r.studentHardLimitPct  = p.getStudentHardLimitPct();
        r.maxStaff             = p.getMaxStaff();
        r.staffSoftLimitPct    = p.getStaffSoftLimitPct();
        r.staffHardLimitPct    = p.getStaffHardLimitPct();
        r.storageGbLimit       = p.getStorageGbLimit();
        r.storageSoftLimitPct  = p.getStorageSoftLimitPct();
        r.storageHardLimitPct  = p.getStorageHardLimitPct();
        r.monthlyPricePaise    = p.getMonthlyPricePaise();
        r.annualPricePaise     = p.getAnnualPricePaise();
        r.currency             = p.getCurrency();
        r.createdAt            = p.getCreatedAt();
        r.updatedAt            = p.getUpdatedAt();
        r.features             = features.stream().map(FeatureCatalogResponse::from).toList();
        r.pendingChanges       = pendingChanges;
        return r;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getTier() { return tier; }
    public String getVersion() { return version; }
    public boolean isPublic() { return isPublic; }
    public boolean isActive() { return isActive; }
    public int getPriorityScore() { return priorityScore; }
    public Integer getMaxStudents() { return maxStudents; }
    public Integer getStudentSoftLimitPct() { return studentSoftLimitPct; }
    public Integer getStudentHardLimitPct() { return studentHardLimitPct; }
    public Integer getMaxStaff() { return maxStaff; }
    public Integer getStaffSoftLimitPct() { return staffSoftLimitPct; }
    public Integer getStaffHardLimitPct() { return staffHardLimitPct; }
    public Integer getStorageGbLimit() { return storageGbLimit; }
    public Integer getStorageSoftLimitPct() { return storageSoftLimitPct; }
    public Integer getStorageHardLimitPct() { return storageHardLimitPct; }
    public Long getMonthlyPricePaise() { return monthlyPricePaise; }
    public Long getAnnualPricePaise() { return annualPricePaise; }
    public String getCurrency() { return currency; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public List<FeatureCatalogResponse> getFeatures() { return features; }
    public List<PlanFeatureChangeResponse> getPendingChanges() { return pendingChanges; }
}
