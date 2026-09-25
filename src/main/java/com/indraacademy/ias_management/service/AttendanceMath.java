package com.indraacademy.ias_management.service;

/**
 * The single Attendance V2 formula, used by every summary, dashboard and notification:
 * attendance % = present / submitted days * 100, rounded to one decimal, 0 when nothing was
 * submitted. Approved leave is informational only — an ABSENT day is absent either way.
 */
public final class AttendanceMath {
    private AttendanceMath() {}

    /** A student's (or a group's) counts over a period. workingDays = submitted days = present + absent. */
    public record Counts(long present, long absent, long approvedLeave) {
        public static final Counts NONE = new Counts(0, 0, 0);

        public long workingDays() {
            return present + absent;
        }

        public double percentage() {
            return AttendanceMath.percentage(present, workingDays());
        }

        public Counts plus(Counts other) {
            return new Counts(present + other.present, absent + other.absent, approvedLeave + other.approvedLeave);
        }
    }

    public static double percentage(long present, long submittedDays) {
        if (submittedDays <= 0) return 0.0;
        return Math.round(present * 1000.0 / submittedDays) / 10.0;
    }
}
