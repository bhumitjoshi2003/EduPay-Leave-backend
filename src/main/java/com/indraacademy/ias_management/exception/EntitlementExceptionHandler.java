package com.indraacademy.ias_management.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts entitlement exceptions to structured 403 responses.
 * Body shape: { code, message, featureKey?, limitType?, planTier?, upgradeRequired }
 */
@RestControllerAdvice
public class EntitlementExceptionHandler {

    @ExceptionHandler(FeatureAccessException.class)
    public ResponseEntity<Map<String, Object>> handleFeatureAccess(FeatureAccessException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "FEATURE_NOT_AVAILABLE");
        body.put("message", ex.getMessage());
        body.put("featureKey", ex.getFeatureKey());
        body.put("planTier", ex.getPlanTier());
        body.put("upgradeRequired", true);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(ResourceLimitException.class)
    public ResponseEntity<Map<String, Object>> handleResourceLimit(ResourceLimitException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "RESOURCE_LIMIT_EXCEEDED");
        body.put("message", ex.getMessage());
        body.put("limitType", ex.getLimitType() != null ? ex.getLimitType().name() : null);
        body.put("currentCount", ex.getCurrentCount());
        body.put("delta", ex.getDelta());
        body.put("hardLimit", ex.getHardLimit());
        body.put("planTier", ex.getPlanTier());
        body.put("upgradeRequired", true);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }
}
