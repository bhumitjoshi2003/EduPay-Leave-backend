package com.indraacademy.ias_management.exception;

import com.indraacademy.ias_management.entity.LimitType;

/**
 * Thrown when adding a resource would exceed the hard quota limit for the school's plan.
 * Controllers should let EntitlementExceptionHandler convert this to a 403.
 */
public class ResourceLimitException extends RuntimeException {

    private final LimitType limitType;
    private final int currentCount;
    private final int delta;
    private final int hardLimit;
    private final String planTier;

    public ResourceLimitException(LimitType limitType, int currentCount, int delta,
                                   int hardLimit, String planTier) {
        super(String.format(
                "Hard limit exceeded for %s: current=%d, adding=%d, hard limit=%d (plan: %s).",
                limitType, currentCount, delta, hardLimit, planTier));
        this.limitType    = limitType;
        this.currentCount = currentCount;
        this.delta        = delta;
        this.hardLimit    = hardLimit;
        this.planTier     = planTier;
    }

    public LimitType getLimitType()   { return limitType; }
    public int getCurrentCount()      { return currentCount; }
    public int getDelta()             { return delta; }
    public int getHardLimit()         { return hardLimit; }
    public String getPlanTier()       { return planTier; }
}
