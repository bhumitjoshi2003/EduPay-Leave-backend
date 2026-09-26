package com.indraacademy.ias_management.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * The single result formula (Results Phase 1), used by My Results, Class Results, the AI result
 * endpoints, the report-card engine and the class overview.
 *
 * <ul>
 *   <li>Percentage = total obtained / total maximum × 100 over every subject the student is
 *       supposed to take, rounded to 2 decimals.</li>
 *   <li>A subject the student takes with no mark is NOT ENTERED: it is never dropped and never an
 *       implicit zero, so the result is incomplete and has no percentage, grade, pass/fail or
 *       rank until every mark is in. (Report cards opt in to counting a missing mark as absent —
 *       {@link Score#percentageCountingMissingAsAbsent()} — because the printed card shows "Ab".)</li>
 *   <li>Rank = standard competition rank by percentage among complete results in the same exam,
 *       class and section: 1 + the number of strictly higher percentages; ties share a rank.</li>
 * </ul>
 */
public final class ResultCalculator {
    private ResultCalculator() {}

    /**
     * One student's result for one exam. obtained/max cover every applicable subject (a missing
     * mark adds its maximum but no marks). percentage, grade and passed are null while incomplete.
     */
    public record Score(double obtained, double max, int subjects, int marksMissing,
                        Double percentage, String grade, Boolean passed) {
        public boolean complete() { return marksMissing == 0 && subjects > 0; }

        /** Report-card rule: a missing mark counts as absent (0). */
        public double percentageCountingMissingAsAbsent() {
            return max > 0 ? round2(obtained / max * 100.0) : 0.0;
        }
    }

    /** One applicable subject: its maximum and the student's mark (null = not entered). */
    public record SubjectMark(int maxMarks, Double obtained) {}

    public static Score score(Collection<SubjectMark> applicable, String gradingSystem) {
        double obtained = 0, max = 0;
        int missing = 0;
        for (SubjectMark s : applicable) {
            max += s.maxMarks();
            if (s.obtained() == null) missing++;
            else obtained += s.obtained();
        }
        boolean complete = missing == 0 && !applicable.isEmpty() && max > 0;
        Double pct = complete ? round2(obtained / max * 100.0) : null;
        return new Score(obtained, max, applicable.size(), missing, pct,
                pct != null ? GradingPolicy.grade(pct, gradingSystem) : null,
                pct != null ? GradingPolicy.passed(pct) : null);
    }

    /**
     * Competition ranks by percentage within one rank group (same exam, class and section).
     * Entries with a null percentage (incomplete) are not ranked.
     */
    public static <K> Map<K, Integer> competitionRanks(Map<K, Double> percentageByKey) {
        Map<K, Integer> ranks = new HashMap<>();
        for (Map.Entry<K, Double> e : percentageByKey.entrySet()) {
            if (e.getValue() == null) continue;
            double mine = round2(e.getValue());
            long higher = percentageByKey.values().stream()
                    .filter(v -> v != null && round2(v) > mine).count();
            ranks.put(e.getKey(), (int) higher + 1);
        }
        return ranks;
    }

    /** Competition ranks grouped by a key (e.g. section) — ranks never cross groups. */
    public static <K, G> Map<K, Integer> competitionRanksWithin(Map<K, Double> percentageByKey, Function<K, G> group) {
        Map<G, Map<K, Double>> byGroup = new HashMap<>();
        percentageByKey.forEach((k, v) -> byGroup.computeIfAbsent(group.apply(k), g -> new HashMap<>()).put(k, v));
        Map<K, Integer> ranks = new HashMap<>();
        byGroup.values().forEach(g -> ranks.putAll(competitionRanks(g)));
        return ranks;
    }

    public static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
