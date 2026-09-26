package com.indraacademy.ias_management.service;

/**
 * The single grading and pass rule for results and report cards. The grading system comes from
 * the school (School.gradingSystem) or a report-card template override: CBSE (A1–E, default),
 * LETTER (A+–F) or PERCENTAGE. Pass/fail uses one fixed pass percentage until a configurable one
 * exists.
 */
public final class GradingPolicy {
    private GradingPolicy() {}

    /** Phase 1 pass mark (%), the rule the report-card overview already used. */
    public static final double PASS_PERCENTAGE = 33.0;

    public static boolean passed(double percentage) {
        return percentage >= PASS_PERCENTAGE;
    }

    /** Normalises a grading-system value; anything unknown or blank means CBSE. */
    public static String system(String gradingSystem) {
        if (gradingSystem == null || gradingSystem.isBlank()) return "CBSE";
        String s = gradingSystem.trim().toUpperCase();
        return s.equals("LETTER") || s.equals("PERCENTAGE") ? s : "CBSE";
    }

    public static String grade(double pct, String gradingSystem) {
        switch (system(gradingSystem)) {
            case "PERCENTAGE":
                return Math.round(pct) + "%";
            case "LETTER":
                if (pct >= 90) return "A+";
                if (pct >= 80) return "A";
                if (pct >= 70) return "B+";
                if (pct >= 60) return "B";
                if (pct >= 50) return "C+";
                if (pct >= 40) return "C";
                if (pct >= 33) return "D";
                return "F";
            default:
                if (pct >= 91) return "A1";
                if (pct >= 81) return "A2";
                if (pct >= 71) return "B1";
                if (pct >= 61) return "B2";
                if (pct >= 51) return "C1";
                if (pct >= 41) return "C2";
                if (pct >= 33) return "D";
                return "E";
        }
    }

    /** CBSE grade point for a CBSE grade (0 for E, absent or any non-CBSE grade). */
    public static double cbseGradePoint(String grade) {
        if (grade == null) return 0.0;
        switch (grade) {
            case "A1": return 10.0;
            case "A2": return 9.0;
            case "B1": return 8.0;
            case "B2": return 7.0;
            case "C1": return 6.0;
            case "C2": return 5.0;
            case "D":  return 4.0;
            default:   return 0.0;
        }
    }
}
