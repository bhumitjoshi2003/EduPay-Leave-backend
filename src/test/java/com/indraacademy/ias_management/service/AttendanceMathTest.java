package com.indraacademy.ias_management.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AttendanceMathTest {

    @Test
    void approvedLeaveNeverShrinksTheDenominator() {
        // 20 submitted days, 18 present, 2 absent (1 on approved leave) → 90%, not 18/19.
        AttendanceMath.Counts counts = new AttendanceMath.Counts(18, 2, 1);
        assertThat(counts.workingDays()).isEqualTo(20);
        assertThat(counts.percentage()).isEqualTo(90.0);
    }

    @Test
    void roundsToOneDecimalAndIsZeroWithoutData() {
        assertThat(AttendanceMath.percentage(2, 3)).isEqualTo(66.7);
        assertThat(AttendanceMath.percentage(0, 0)).isZero();
        assertThat(AttendanceMath.Counts.NONE.percentage()).isZero();
        assertThat(new AttendanceMath.Counts(1, 0, 0).plus(new AttendanceMath.Counts(0, 1, 1)))
                .isEqualTo(new AttendanceMath.Counts(1, 1, 1));
    }
}
