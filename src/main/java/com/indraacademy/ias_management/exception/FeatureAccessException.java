package com.indraacademy.ias_management.exception;

/**
 * Thrown when a school attempts to access a feature not included in its plan.
 * Controllers should let EntitlementExceptionHandler convert this to a 403.
 */
public class FeatureAccessException extends RuntimeException {

    private final String featureKey;
    private final String planTier;

    public FeatureAccessException(String featureKey, String planTier) {
        super("Feature '" + featureKey + "' is not available on the " + planTier + " plan.");
        this.featureKey = featureKey;
        this.planTier   = planTier;
    }

    public FeatureAccessException(String message) {
        super(message);
        this.featureKey = null;
        this.planTier   = null;
    }

    public String getFeatureKey() { return featureKey; }
    public String getPlanTier()   { return planTier; }
}
