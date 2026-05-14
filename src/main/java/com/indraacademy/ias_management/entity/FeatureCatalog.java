package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;

import java.util.HashSet;
import java.util.Set;

/**
 * Master list of every gateable feature the platform supports.
 * Rows are seeded at startup and never changed without a code deploy.
 * is_always_on = true means the feature is documented here but never gated.
 */
@Entity
@Table(name = "feature_catalog")
public class FeatureCatalog {

    @Id
    @Column(name = "feature_key", length = 60)
    private String featureKey;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** FINANCE, ACADEMICS, COMMUNICATION, ADMIN */
    @Column(length = 50)
    private String category;

    /** true = always available, shown here for documentation only */
    @Column(name = "is_always_on", nullable = false)
    private boolean isAlwaysOn = false;

    /**
     * Feature keys that must be present in the plan before this feature can be added.
     * Example: PAYMENT_COLLECTION depends on FEE_MANAGEMENT.
     * Seeded at startup; validated in PlanService on add/remove.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "feature_catalog_dependencies",
            joinColumns = @JoinColumn(name = "feature_key"))
    @Column(name = "depends_on_feature_key", length = 60)
    private Set<String> dependsOn = new HashSet<>();

    public FeatureCatalog() {}

    public FeatureCatalog(String featureKey, String displayName, String description,
                          String category, boolean isAlwaysOn) {
        this.featureKey  = featureKey;
        this.displayName = displayName;
        this.description = description;
        this.category    = category;
        this.isAlwaysOn  = isAlwaysOn;
    }

    public String getFeatureKey() { return featureKey; }
    public void setFeatureKey(String featureKey) { this.featureKey = featureKey; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public boolean isAlwaysOn() { return isAlwaysOn; }
    public void setAlwaysOn(boolean isAlwaysOn) { this.isAlwaysOn = isAlwaysOn; }

    public Set<String> getDependsOn() { return dependsOn; }
    public void setDependsOn(Set<String> dependsOn) { this.dependsOn = dependsOn; }
}
