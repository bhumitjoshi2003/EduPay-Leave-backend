package com.indraacademy.ias_management.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResultCalculatorTest {

    @Test
    void percentageIsTotalObtainedOverTotalMaximum() {
        ResultCalculator.Score s = ResultCalculator.score(List.of(
                new ResultCalculator.SubjectMark(100, 80.0), new ResultCalculator.SubjectMark(50, 25.0)), "CBSE");
        assertThat(s.percentage()).isEqualTo(70.0);   // 105 / 150, not the mean of 80% and 50%
        assertThat(s.grade()).isEqualTo("B2");
        assertThat(s.passed()).isTrue();
        assertThat(s.complete()).isTrue();
    }

    @Test
    void missingMarkMakesTheResultIncompleteButKeepsTheSubject() {
        ResultCalculator.Score s = ResultCalculator.score(List.of(
                new ResultCalculator.SubjectMark(100, 90.0), new ResultCalculator.SubjectMark(100, null)), "CBSE");
        assertThat(s.complete()).isFalse();
        assertThat(s.marksMissing()).isEqualTo(1);
        assertThat(s.max()).isEqualTo(200.0);
        assertThat(s.percentage()).isNull();
        assertThat(s.grade()).isNull();
        assertThat(s.passed()).isNull();
        assertThat(s.percentageCountingMissingAsAbsent()).isEqualTo(45.0);   // report-card rule, explicit
    }

    @Test
    void competitionRanksShareTiesAndSkipAndIgnoreIncomplete() {
        Map<String, Double> pct = new HashMap<>();
        pct.put("a", 75.0);
        pct.put("b", 75.0);
        pct.put("c", 70.0);
        pct.put("d", null);
        assertThat(ResultCalculator.competitionRanks(pct)).containsExactlyInAnyOrderEntriesOf(Map.of("a", 1, "b", 1, "c", 3));
    }

    @Test
    void ranksNeverCrossSections() {
        Map<String, Double> pct = Map.of("a", 90.0, "b", 60.0, "c", 50.0);
        Map<String, String> section = Map.of("a", "A", "b", "B", "c", "B");
        assertThat(ResultCalculator.competitionRanksWithin(pct, section::get))
                .containsExactlyInAnyOrderEntriesOf(Map.of("a", 1, "b", 1, "c", 2));
    }

    @Test
    void gradingPolicyIsTheOnlyScale() {
        assertThat(GradingPolicy.grade(91, "CBSE")).isEqualTo("A1");
        assertThat(GradingPolicy.grade(32.9, null)).isEqualTo("E");
        assertThat(GradingPolicy.grade(55, "LETTER")).isEqualTo("C+");
        assertThat(GradingPolicy.grade(66.6, "PERCENTAGE")).isEqualTo("67%");
        assertThat(GradingPolicy.passed(33.0)).isTrue();
        assertThat(GradingPolicy.passed(32.99)).isFalse();
        assertThat(GradingPolicy.cbseGradePoint("B1")).isEqualTo(8.0);
    }
}
