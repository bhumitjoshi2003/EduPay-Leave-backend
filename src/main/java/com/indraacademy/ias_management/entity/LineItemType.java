package com.indraacademy.ias_management.entity;

/** Distinguishes a StudentFeesLineItem sourced from the dynamic FeeHead configuration
 * (FEE_HEAD) from the distance-based bus fee (BUS), which has no FeeHead of its own. Kept
 * to exactly these two today — extend only if a genuinely new charge category (not
 * expressible as a FeeHead) is introduced later. */
public enum LineItemType {
    FEE_HEAD,
    BUS
}
