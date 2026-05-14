package com.indraacademy.ias_management.dto;

import com.indraacademy.ias_management.entity.SchoolEffectiveEntitlement;
import com.indraacademy.ias_management.entity.SchoolSubscription;

import java.util.List;

/** Combined view of a school's subscription + resolved entitlement state. */
public class SchoolSubscriptionResponse {

    // Subscription record
    public Long subscriptionId;
    public Long schoolId;
    public Long planId;
    public String status;
    public String trialStartAt;
    public String trialEndsAt;
    public String activatedAt;
    public String expiresAt;
    public String graceEndsAt;
    public String createdBy;
    public String notes;

    // Resolved entitlement state
    public String planName;
    public String planTier;
    public String planVersion;
    public Integer resolvedPriorityScore;
    public String entitlementSource;
    public Integer maxStudents;
    public Integer maxStaff;
    public Integer storageGbLimit;
    public String lastRebuiltAt;
    public String rebuiltBy;

    // Active feature keys
    public List<String> featureKeys;

    public static SchoolSubscriptionResponse from(SchoolSubscription sub,
                                                   SchoolEffectiveEntitlement ent,
                                                   List<String> featureKeys) {
        SchoolSubscriptionResponse r = new SchoolSubscriptionResponse();
        r.subscriptionId       = sub.getId();
        r.schoolId             = sub.getSchoolId();
        r.planId               = sub.getPlanId();
        r.status               = sub.getStatus();
        r.trialStartAt         = sub.getTrialStartAt()  != null ? sub.getTrialStartAt().toString()  : null;
        r.trialEndsAt          = sub.getTrialEndsAt()   != null ? sub.getTrialEndsAt().toString()   : null;
        r.activatedAt          = sub.getActivatedAt()   != null ? sub.getActivatedAt().toString()   : null;
        r.expiresAt            = sub.getExpiresAt()     != null ? sub.getExpiresAt().toString()     : null;
        r.graceEndsAt          = sub.getGraceEndsAt()   != null ? sub.getGraceEndsAt().toString()   : null;
        r.createdBy            = sub.getCreatedBy();
        r.notes                = sub.getNotes();

        if (ent != null) {
            r.planName             = ent.getPlanName();
            r.planTier             = ent.getPlanTier();
            r.planVersion          = ent.getPlanVersion();
            r.resolvedPriorityScore = ent.getResolvedPriorityScore();
            r.entitlementSource    = ent.getEntitlementSource();
            r.maxStudents          = ent.getMaxStudents();
            r.maxStaff             = ent.getMaxStaff();
            r.storageGbLimit       = ent.getStorageGbLimit();
            r.lastRebuiltAt        = ent.getLastRebuiltAt() != null ? ent.getLastRebuiltAt().toString() : null;
            r.rebuiltBy            = ent.getRebuiltBy();
        }

        r.featureKeys = featureKeys;
        return r;
    }
}
