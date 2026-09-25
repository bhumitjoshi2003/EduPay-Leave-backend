package com.indraacademy.ias_management.entity;

/** Kind of scheduled assessment (V79). */
public enum AssessmentType {
    CLASS_TEST("Class Test"),
    UNIT_TEST("Unit Test"),
    PRACTICAL("Practical"),
    PRE_BOARD("Pre-Board"),
    INTERNAL_ASSESSMENT("Internal Assessment"),
    OTHER("Assessment");

    private final String label;

    AssessmentType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
