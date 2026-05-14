package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;

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
}
