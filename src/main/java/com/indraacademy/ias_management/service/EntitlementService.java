package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.exception.FeatureAccessException;
import com.indraacademy.ias_management.exception.ResourceLimitException;
import com.indraacademy.ias_management.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Central access-control service. All feature gates and resource limit checks go through here.
 * Backend is always authoritative — frontend hiding is UX only.
 *
 * Three primary methods from day one:
 *   hasFeature(schoolId, featureKey)         → boolean feature gate
 *   checkLimit(schoolId, limitType, delta)   → LimitCheckResult (throws on HARD_BLOCKED)
 *   getEffectiveEntitlement(schoolId)        → full resolved subscription state
 */
@Service
public class EntitlementService {

    private static final Logger log = LoggerFactory.getLogger(EntitlementService.class);

    @Autowired private SchoolEffectiveEntitlementRepository entitlementRepo;
    @Autowired private SchoolEntitlementFeatureRepository entitlementFeatureRepo;
    @Autowired private StudentRepository studentRepository;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private AdminRepository adminRepository;

    // ── 1. Feature gate ───────────────────────────────────────────────────────

    /**
     * Returns true if the school's resolved entitlement includes the given feature.
     * EXPIRED schools always return false.
     * Schools with no entitlement row (not yet on a subscription) return false.
     */
    public boolean hasFeature(Long schoolId, String featureKey) {
        if (schoolId == null) return false;
        return entitlementFeatureRepo.existsBySchoolIdAndFeatureKey(schoolId, featureKey);
    }

    /**
     * Asserts the school has a feature; throws FeatureAccessException (→ 403) if not.
     * Use this in service methods that gate a capability behind a plan feature.
     */
    public void requireFeature(Long schoolId, String featureKey) {
        if (!hasFeature(schoolId, featureKey)) {
            SchoolEffectiveEntitlement ent = entitlementRepo.findById(schoolId).orElse(null);
            String planTier = ent != null ? ent.getPlanTier() : "UNKNOWN";
            throw new FeatureAccessException(featureKey, planTier);
        }
    }

    // ── 2. Quota gate ─────────────────────────────────────────────────────────

    /**
     * Checks whether adding {@code delta} units of {@code limitType} stays within plan limits.
     *
     * delta can be >1 for bulk operations (e.g. bulk import of 500 students → delta=500).
     *
     * Rules:
     *   projected ≤ softLimit → OK
     *   softLimit < projected ≤ hardLimit → SOFT_WARN (logs warning, returns SOFT_WARN)
     *   projected > hardLimit → throws ResourceLimitException (403)
     *
     * null max limit = unlimited → always returns OK.
     * EXPIRED school → throws FeatureAccessException.
     */
    public LimitCheckResult checkLimit(Long schoolId, LimitType limitType, int delta) {
        SchoolEffectiveEntitlement ent = getEffectiveEntitlement(schoolId);

        if ("EXPIRED".equals(ent.getSubscriptionStatus())) {
            throw new FeatureAccessException("Subscription has expired. Please renew to continue.");
        }

        Integer maxLimit = getMaxLimit(ent, limitType);
        if (maxLimit == null) return LimitCheckResult.OK; // unlimited

        int currentCount = getCurrentCount(schoolId, limitType);
        int projected    = currentCount + delta;

        int softThreshold = (int) Math.ceil(maxLimit * getSoftLimitPct(ent, limitType) / 100.0);
        int hardThreshold = (int) Math.ceil(maxLimit * getHardLimitPct(ent, limitType) / 100.0);

        if (projected > hardThreshold) {
            throw new ResourceLimitException(limitType, currentCount, delta, hardThreshold, ent.getPlanTier());
        }

        if (projected > softThreshold) {
            log.warn("SOFT LIMIT WARNING: school={} limitType={} current={} delta={} softThreshold={} hardThreshold={}",
                    schoolId, limitType, currentCount, delta, softThreshold, hardThreshold);
            return LimitCheckResult.SOFT_WARN;
        }

        return LimitCheckResult.OK;
    }

    // ── 3. Full resolved state ────────────────────────────────────────────────

    /**
     * Returns the full resolved subscription state for a school.
     * Throws IllegalStateException if no entitlement exists (school has no subscription).
     */
    public SchoolEffectiveEntitlement getEffectiveEntitlement(Long schoolId) {
        return entitlementRepo.findById(schoolId)
                .orElseThrow(() -> new IllegalStateException(
                        "No entitlement found for schoolId=" + schoolId +
                        ". Assign a subscription first."));
    }

    /**
     * Returns feature keys for the school's current entitlement.
     * Used by /auth/me to return the feature list to the frontend.
     */
    public List<String> getEffectiveFeatureKeys(Long schoolId) {
        return entitlementFeatureRepo.findBySchoolId(schoolId)
                .stream()
                .map(SchoolEntitlementFeature::getFeatureKey)
                .toList();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Integer getMaxLimit(SchoolEffectiveEntitlement ent, LimitType type) {
        return switch (type) {
            case STUDENTS   -> ent.getMaxStudents();
            case STAFF      -> ent.getMaxStaff();
            case STORAGE_GB -> ent.getStorageGbLimit();
        };
    }

    private int getSoftLimitPct(SchoolEffectiveEntitlement ent, LimitType type) {
        return switch (type) {
            case STUDENTS   -> ent.getStudentSoftLimitPct()   != null ? ent.getStudentSoftLimitPct()   : 90;
            case STAFF      -> ent.getStaffSoftLimitPct()     != null ? ent.getStaffSoftLimitPct()     : 90;
            case STORAGE_GB -> ent.getStorageSoftLimitPct()   != null ? ent.getStorageSoftLimitPct()   : 90;
        };
    }

    private int getHardLimitPct(SchoolEffectiveEntitlement ent, LimitType type) {
        return switch (type) {
            case STUDENTS   -> ent.getStudentHardLimitPct()   != null ? ent.getStudentHardLimitPct()   : 105;
            case STAFF      -> ent.getStaffHardLimitPct()     != null ? ent.getStaffHardLimitPct()     : 105;
            case STORAGE_GB -> ent.getStorageHardLimitPct()   != null ? ent.getStorageHardLimitPct()   : 105;
        };
    }

    private int getCurrentCount(Long schoolId, LimitType type) {
        return switch (type) {
            case STUDENTS   -> (int) studentRepository.countByStatusAndSchoolId(StudentStatus.ACTIVE, schoolId);
            case STAFF      -> (int) (teacherRepository.countBySchoolId(schoolId)
                                     + adminRepository.countBySchoolId(schoolId));
            case STORAGE_GB -> 0; // File storage tracking is event-driven — placeholder for now
        };
    }
}
