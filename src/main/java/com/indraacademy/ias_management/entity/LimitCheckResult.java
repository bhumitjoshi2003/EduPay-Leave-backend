package com.indraacademy.ias_management.entity;

/**
 * Result of EntitlementService.checkLimit().
 *
 * OK          — projected count is below soft limit. Proceed normally.
 * SOFT_WARN   — projected count is between soft and hard limit. Log a warning; proceed.
 * HARD_BLOCKED — projected count exceeds hard limit. Throw ResourceLimitException (403).
 */
public enum LimitCheckResult {
    OK,
    SOFT_WARN,
    HARD_BLOCKED
}
