package com.indraacademy.ias_management.dto;

import com.indraacademy.ias_management.entity.FeatureCatalog;

import java.util.Set;

public class FeatureCatalogResponse {
    private String featureKey;
    private String displayName;
    private String description;
    private String category;
    private boolean isAlwaysOn;
    private Set<String> dependsOn;

    public static FeatureCatalogResponse from(FeatureCatalog f) {
        FeatureCatalogResponse r = new FeatureCatalogResponse();
        r.featureKey  = f.getFeatureKey();
        r.displayName = f.getDisplayName();
        r.description = f.getDescription();
        r.category    = f.getCategory();
        r.isAlwaysOn  = f.isAlwaysOn();
        r.dependsOn   = f.getDependsOn();
        return r;
    }

    public String getFeatureKey() { return featureKey; }
    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public String getCategory() { return category; }
    public boolean isAlwaysOn() { return isAlwaysOn; }
    public Set<String> getDependsOn() { return dependsOn; }
}
