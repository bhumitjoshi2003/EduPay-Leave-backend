package com.indraacademy.ias_management.scheduler;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the academic-calendar audit's fix to StudentClassProgressionScheduler — it was
 * previously hardcoded to promote students once a year, on 26th March only, which only
 * happened to be correct for the April-default school. computePromotionMonth generalizes
 * this to "the month before the school's own configured start month," mirroring
 * StudentFeesGenerationService's identical gating formula.
 */
class StudentClassProgressionSchedulerTest {

    private final StudentClassProgressionScheduler scheduler = new StudentClassProgressionScheduler();

    @Test
    void aprilStartSchool_promotesInMarch() {
        assertThat(scheduler.computePromotionMonth(4)).isEqualTo(3);
    }

    @Test
    void januaryStartSchool_promotesInDecember() {
        assertThat(scheduler.computePromotionMonth(1)).isEqualTo(12);
    }

    @Test
    void julyStartSchool_promotesInJune() {
        assertThat(scheduler.computePromotionMonth(7)).isEqualTo(6);
    }

    @Test
    void decemberStartSchool_promotesInNovember() {
        assertThat(scheduler.computePromotionMonth(12)).isEqualTo(11);
    }
}
