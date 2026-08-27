package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.FeeConfigType;
import com.indraacademy.ias_management.entity.FeeFrequency;
import com.indraacademy.ias_management.entity.FeeHead;
import com.indraacademy.ias_management.entity.FeeStructureRule;
import com.indraacademy.ias_management.entity.LineItemType;
import com.indraacademy.ias_management.entity.SnapshotStatus;
import com.indraacademy.ias_management.entity.StudentFeeConfig;
import com.indraacademy.ias_management.entity.StudentFees;
import com.indraacademy.ias_management.repository.AcademicSessionRepository;
import com.indraacademy.ias_management.repository.BusFeesRepository;
import com.indraacademy.ias_management.repository.FeeStructureRuleRepository;
import com.indraacademy.ias_management.repository.StudentFeeConfigRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Single source of truth for "how much does a class/session owe for a given academic
 * month" under the dynamic FeeHead + FeeStructureRule system — the fee-head-driven
 * replacement for the legacy fixed-column FeeStructure entity, which the admin fee-
 * structure UI (fee-structure.component.ts) stopped writing to some time ago.
 *
 * Extracted from StudentFeesService (formerly private calculateDynamicMonthFeeRupees /
 * feeAppliesThisAcademicMonth) so FeeReminderService can use the exact same proven logic
 * instead of a fourth independent reimplementation — this scheduling rule was already
 * drifting across StudentFeesService, InvoiceGenerationService, and the frontend's
 * fees.component.ts before this extraction; consolidating at least these two stops that.
 *
 * Callers still choosing between this and the legacy FeeStructure entity (see
 * FeeReminderService.getOverdueStudents) should treat this as authoritative when it has
 * any active rules for the class/session — the admin's real, currently-maintained fee
 * configuration lives here, not in the legacy table.
 */
@Service
public class FeeCalculationService {

    private static final Logger log = LoggerFactory.getLogger(FeeCalculationService.class);

    @Autowired private FeeStructureRuleRepository feeStructureRuleRepository;
    @Autowired private AcademicSessionRepository academicSessionRepository;
    @Autowired private BusFeesRepository busFeesRepository;
    @Autowired private StudentFeeConfigRepository studentFeeConfigRepository;
    @Autowired private ObjectMapper objectMapper;

    /**
     * Active dynamic fee rules for a class, as of today. Empty (never null) if the academic
     * session doesn't exist yet or no fee heads have been configured for this class/session —
     * callers should treat an empty list as "no dynamic configuration," not "zero fees."
     */
    public List<FeeStructureRule> loadActiveRules(Long schoolId, String session, String className) {
        return loadActiveRules(schoolId, session, className, LocalDate.now());
    }

    /**
     * Active dynamic fee rules for a class, as of a specific date — lets a caller ask "what
     * was the rule for this fee head on the day this historical academic month was actually
     * due," instead of always getting today's price. This is what makes generation/backfill/
     * recalculation stable: a snapshot computed for a past month keeps using the rule that was
     * active back then, even if an admin has since changed pricing.
     */
    public List<FeeStructureRule> loadActiveRules(Long schoolId, String session, String className, LocalDate asOfDate) {
        Optional<AcademicSession> sessionOpt = academicSessionRepository.findBySchoolIdAndLabel(schoolId, session);
        if (sessionOpt.isEmpty()) {
            log.warn("Academic session '{}' not found for schoolId={}.", session, schoolId);
            return Collections.emptyList();
        }
        return feeStructureRuleRepository.findActiveRules(schoolId, sessionOpt.get().getId(), className, asOfDate);
    }

    /** Total fee (rupees) for one academic month, summed across every active fee head
     * that applies to that month. Does NOT include bus fees — those are a separate,
     * distance-based lookup callers add on top (see FeeReminderService/StudentFeesService). */
    public double calculateMonthFeeRupees(int academicMonth, List<FeeStructureRule> rules) {
        double total = 0;
        for (FeeStructureRule rule : rules) {
            FeeHead head = rule.getFeeHead();
            if (head == null || !head.isActive()) continue;
            if (appliesThisAcademicMonth(academicMonth, head)) {
                total += rule.getAmount() / 100.0; // paise → rupees
            }
        }
        return total;
    }

    /** Whether a fee head is due in the given academic month — from its explicit dueMonths
     * JSON, or (if left at the all-12-months admin-form default) derived from its frequency. */
    public boolean appliesThisAcademicMonth(int academicMonth, FeeHead head) {
        List<Integer> dueMonths;
        try {
            dueMonths = objectMapper.readValue(head.getDueMonths(), new TypeReference<List<Integer>>() {});
        } catch (Exception e) {
            log.warn("Could not parse dueMonths for fee head id={}: {}", head.getId(), head.getDueMonths());
            return false;
        }
        if (dueMonths.size() == 12) {
            FeeFrequency freq = head.getFrequency();
            if (freq == null) return false;
            return switch (freq) {
                case MONTHLY -> true;
                case QUARTERLY -> academicMonth % 3 == 1;    // months 1, 4, 7, 10
                case SEMI_ANNUAL -> academicMonth == 1 || academicMonth == 7;
                case ANNUAL, ONE_TIME -> academicMonth == 1;
            };
        }
        return dueMonths.contains(academicMonth);
    }

    /**
     * Whether a fee head applies in a student's own FIRST generated row, given the academic
     * month they're joining in — a different question from appliesThisAcademicMonth, which
     * only ever asks about a school-wide fixed due-month. A mid-session joiner still owes the
     * billing period they're joining into (even if partial), so this returns true whenever the
     * join month falls anywhere within — not just exactly on — the period that would otherwise
     * govern it. Must only be used for a student's first row; every subsequent row uses the
     * unchanged appliesThisAcademicMonth.
     *
     * Respects an admin's genuinely CUSTOM dueMonths list (dueMonths.size() != 12, i.e. not
     * left at the "let frequency decide" default) instead of overriding it with frequency
     * alone — charges at join only if some configured due-month has already occurred this
     * academic year at-or-before the join month (the period the student is joining mid-way
     * through, or after); if every custom due-month is still ahead of the join month, there's
     * nothing to catch up on and the normal schedule picks them up at the next one naturally.
     */
    public boolean appliesAtJoin(int joinAcademicMonth, FeeHead head) {
        if (appliesThisAcademicMonth(joinAcademicMonth, head)) return true;

        List<Integer> dueMonths;
        try {
            dueMonths = objectMapper.readValue(head.getDueMonths(), new TypeReference<List<Integer>>() {});
        } catch (Exception e) {
            log.warn("Could not parse dueMonths for fee head id={}: {}", head.getId(), head.getDueMonths());
            return false;
        }

        if (dueMonths.size() == 12) {
            // Frequency-derived default — every periodic/annual/one-time head has some
            // billing period already in progress at any join month (MONTHLY already matched
            // above via appliesThisAcademicMonth), so charge it.
            FeeFrequency freq = head.getFrequency();
            if (freq == null) return false;
            return switch (freq) {
                case MONTHLY -> true; // unreachable in practice — MONTHLY already matches above
                case QUARTERLY, SEMI_ANNUAL, ANNUAL, ONE_TIME -> true;
            };
        }

        // Custom schedule: charge only if the student is joining at-or-after a due-month
        // the admin actually configured.
        return dueMonths.stream().anyMatch(m -> m <= joinAcademicMonth);
    }

    private static final String[] CALENDAR_MONTH_NAMES = {
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"
    };

    /** Display name for an academic month (1 = the school's own configured start month,
     * whatever calendar month that is — never assumed to be April). Relocated from
     * FeeReminderService so every caller that needs to show a real month name (payment
     * emails, receipts, reminders) shares one correctly-parameterized implementation
     * instead of each maintaining its own hardcoded calendar array. */
    public String getMonthName(int academicMonth, int startMonth) {
        if (academicMonth < 1 || academicMonth > 12) return "Unknown";
        int calendarMonthIndex = (startMonth - 1 + academicMonth - 1) % 12;
        return CALENDAR_MONTH_NAMES[calendarMonthIndex];
    }

    /** Maps an academic month number (1 = startMonth ... 12 = startMonth-1) to the 1st day
     * of that calendar month, using the session's start and end years. Relocated from
     * FeeReminderService so generation/backfill/recalculation can share the exact same
     * academic-month-to-calendar-date math the reminder system already relies on. */
    public LocalDate academicMonthStart(int academicMonth, int startYear, int endYear, int startMonth) {
        int calendarMonth = ((startMonth - 1 + academicMonth - 1) % 12) + 1;
        int year = calendarMonth >= startMonth ? startYear : endYear;
        return LocalDate.of(year, calendarMonth, 1);
    }

    /** Parses "2025-2026" -> [2025, 2026]. */
    public int[] parseSession(String session) {
        String[] parts = session.split("-");
        if (parts.length != 2) throw new IllegalArgumentException("Invalid session format. Expected 'YYYY-YYYY'.");
        return new int[]{ Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) };
    }

    /** Bus fee (rupees) for a distance bracket, as configured for a school/academic year.
     * Explicit schoolId parameter (not SecurityUtil-derived) so this is safe to call from
     * scheduled/batch generation code with no request-bound security context. Zero (never
     * null) if no bracket matches or the student has no distance recorded. */
    public BigDecimal loadBusFee(Long schoolId, Double distance, String academicYear) {
        if (distance == null || academicYear == null) return BigDecimal.ZERO;
        BigDecimal fee = busFeesRepository.findFeesByDistanceAndAcademicYearAndSchoolId(distance, academicYear, schoolId);
        return fee != null ? fee : BigDecimal.ZERO;
    }

    /** Result of applying a StudentFeeConfig (waiver/discount/custom-amount) to a fee head's
     * ruled amount. grossPaise is the rule's own amount, possibly overridden by CUSTOM_AMOUNT;
     * discountPaise is what was subtracted. Mirrors InvoiceGenerationService's existing
     * WAIVER/DISCOUNT_PERCENT/DISCOUNT_FIXED/CUSTOM_AMOUNT switch exactly, so the two systems
     * don't silently drift on what a given config actually does. OPT_OUT zeroes both — callers
     * for optional fee heads should still check feeHead.isOptional() && configType == OPT_OUT
     * themselves and skip the fee head entirely, matching Invoice's pattern, rather than
     * relying on a zeroed amount alone. */
    public record AppliedFeeHeadAmount(long grossPaise, long discountPaise) {
        public long netPaise() { return grossPaise - discountPaise; }
    }

    public AppliedFeeHeadAmount applyStudentFeeConfig(long ruleAmountPaise, StudentFeeConfig config) {
        if (config == null) return new AppliedFeeHeadAmount(ruleAmountPaise, 0L);
        long gross = ruleAmountPaise;
        long discount = 0L;
        switch (config.getConfigType()) {
            case WAIVER -> discount = gross;
            case DISCOUNT_PERCENT -> discount = BigDecimal.valueOf(gross)
                    .multiply(config.getValue())
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                    .longValue();
            case DISCOUNT_FIXED -> discount = Math.min(config.getValue().longValue(), gross);
            case CUSTOM_AMOUNT -> gross = config.getValue().longValue();
            case OPT_OUT -> { gross = 0L; discount = 0L; }
        }
        return new AppliedFeeHeadAmount(gross, discount);
    }

    /**
     * Whether a class/session has enough configuration to confidently generate fee snapshots
     * at all — checked ONCE per student/class/session, before any StudentFees row is created
     * for them, so an unconfigured class never silently produces a full year of frozen ₹0.00
     * rows. Deliberately narrower than "does this specific month have an active rule" (see
     * SnapshotStatus.NO_ACTIVE_RULE_THIS_MONTH below, which is a per-month, non-blocking
     * signal) — this checks two structural preconditions instead:
     *   1. The AcademicSession for the target session label must exist.
     *   2. At least one FeeStructureRule must have EVER been configured for this class in
     *      this session (regardless of effective-date range) — a class with zero rules,
     *      full stop, has no fee structure to compute from, as opposed to a class whose
     *      rules simply don't cover one particular month (a narrower, legitimate gap).
     */
    public record FeeConfigurationStatus(boolean valid, String reason) {
        public static FeeConfigurationStatus ok() {
            return new FeeConfigurationStatus(true, null);
        }
        public static FeeConfigurationStatus fail(String reason) {
            return new FeeConfigurationStatus(false, reason);
        }
    }

    public FeeConfigurationStatus validateFeeConfiguration(Long schoolId, String session, String className) {
        Optional<AcademicSession> sessionOpt = academicSessionRepository.findBySchoolIdAndLabel(schoolId, session);
        if (sessionOpt.isEmpty()) {
            return FeeConfigurationStatus.fail(
                    "AcademicSession not found for schoolId=" + schoolId + ", session='" + session + "'");
        }
        boolean hasAnyRuleEver = !feeStructureRuleRepository
                .findBySchoolIdAndAcademicSessionIdAndClassName(schoolId, sessionOpt.get().getId(), className)
                .isEmpty();
        if (!hasAnyRuleEver) {
            return FeeConfigurationStatus.fail(
                    "No FeeStructureRule configured for schoolId=" + schoolId + ", session='" + session
                            + "', className='" + className + "'");
        }
        return FeeConfigurationStatus.ok();
    }

    /**
     * The full, stable snapshot for one StudentFees row — sub-phase 1b's actual deliverable.
     * baseAmountDue/discountAmount/busFeeDue/ruleSnapshotJson are written ONCE, at generation
     * time, and must never be silently recomputed afterward (see StudentFeesGenerationService
     * and StudentFeesService.createDefaultStudentFees, the only two callers). Callers should
     * have already called validateFeeConfiguration for this student/class/session and skipped
     * entirely on failure — this method still degrades gracefully (via SnapshotStatus) if
     * called without that check, but the blocking guarantee lives in the callers, not here.
     */
    public record MonthSnapshot(
            BigDecimal baseAmountDue,
            BigDecimal busFeeDue,
            BigDecimal discountAmount,
            String ruleSnapshotJson,
            List<Long> newlyChargedOneTimeFeeHeadIds,
            SnapshotStatus status,
            List<LineItemSnapshot> lineItems
    ) {}

    /**
     * One line of the authoritative, immutable per-charge breakdown behind baseAmountDue/
     * busFeeDue — built from the exact same gross/discount figures computeMonthSnapshot's
     * fee-head loop (and bus-fee lookup) already produces, never a second calculation.
     * feeHeadName/frequency are copied at generation time specifically so a later FeeHead
     * rename, frequency change, or deletion can never alter what an already-generated line
     * item says. lineItemType is "FEE_HEAD" or "BUS"; feeHeadId/feeHeadCode/frequency are
     * null for a BUS line (bus fee has no FeeHead).
     */
    public record LineItemSnapshot(
            String lineItemType,
            Long feeHeadId,
            String feeHeadCode,
            String feeHeadName,
            String frequency,
            long grossPaise,
            long discountPaise,
            String discountConfigType
    ) {
        public long netPaise() { return grossPaise - discountPaise; }
    }

    /**
     * Computes one StudentFees row's snapshot: sums every FeeHead due this academic month
     * (respecting frequency/custom dueMonths — via appliesAtJoin for a student's first-ever
     * row, appliesThisAcademicMonth for every row after that), applies the student's active
     * StudentFeeConfig (waiver/discount/custom-amount/opt-out), adds the distance-based bus
     * fee if the student takes the bus, and records which ONE_TIME fee heads were charged
     * (so the caller can persist them to StudentOneTimeFeeChargedRepository — this method
     * only reads that dedup state, it never writes it).
     *
     * @param asOfDate                        the real calendar date THIS academic month falls
     *                                         on — rules and student configs are resolved as of
     *                                         this date, not today, so a snapshot computed for a
     *                                         past or future month stays correct even if pricing
     *                                         changes later.
     * @param isFirstRow                      true only for a student's very first generated row
     *                                        (their join month) — uses appliesAtJoin instead of
     *                                        appliesThisAcademicMonth for periodic/annual heads.
     * @param alreadyChargedOneTimeFeeHeadIds ONE_TIME fee heads already charged to this student,
     *                                        in any prior session AND any earlier row already
     *                                        processed in the current generation run — the
     *                                        caller must fold newlyChargedOneTimeFeeHeadIds from
     *                                        each row into this set before computing the next.
     */
    public MonthSnapshot computeMonthSnapshot(
            Long schoolId,
            String session,
            String className,
            String studentId,
            int academicMonth,
            boolean isFirstRow,
            LocalDate asOfDate,
            Boolean takesBus,
            Double distance,
            Set<Long> alreadyChargedOneTimeFeeHeadIds) {

        List<FeeStructureRule> rules = loadActiveRules(schoolId, session, className, asOfDate);
        SnapshotStatus status = rules.isEmpty() ? SnapshotStatus.NO_ACTIVE_RULE_THIS_MONTH : SnapshotStatus.COMPUTED;

        Map<Long, StudentFeeConfig> configByFeeHead = Collections.emptyMap();
        Optional<AcademicSession> sessionOpt = academicSessionRepository.findBySchoolIdAndLabel(schoolId, session);
        if (sessionOpt.isPresent()) {
            List<StudentFeeConfig> configs = studentFeeConfigRepository.findActiveConfigs(
                    schoolId, studentId, sessionOpt.get().getId(), asOfDate);
            configByFeeHead = configs.stream()
                    .collect(Collectors.toMap(c -> c.getFeeHead().getId(), c -> c, (a, b) -> b));
        }

        long grossTotalPaise = 0L;
        long discountTotalPaise = 0L;
        List<Map<String, Object>> feeHeadSnapshots = new ArrayList<>();
        List<Long> newlyChargedOneTime = new ArrayList<>();
        List<LineItemSnapshot> lineItems = new ArrayList<>();

        for (FeeStructureRule rule : rules) {
            FeeHead head = rule.getFeeHead();
            if (head == null || !head.isActive()) continue;

            boolean isOneTime = head.getFrequency() == FeeFrequency.ONE_TIME;
            if (isOneTime && alreadyChargedOneTimeFeeHeadIds.contains(head.getId())) {
                continue; // already charged — ONE_TIME heads are never charged twice, ever
            }

            boolean applies = isFirstRow ? appliesAtJoin(academicMonth, head) : appliesThisAcademicMonth(academicMonth, head);
            if (!applies) continue;

            StudentFeeConfig config = configByFeeHead.get(head.getId());
            if (head.isOptional() && config != null && config.getConfigType() == FeeConfigType.OPT_OUT) {
                continue;
            }

            AppliedFeeHeadAmount applied = applyStudentFeeConfig(rule.getAmount(), config);
            grossTotalPaise += applied.grossPaise();
            discountTotalPaise += applied.discountPaise();

            Map<String, Object> headSnapshot = new LinkedHashMap<>();
            headSnapshot.put("feeHeadId", head.getId());
            headSnapshot.put("code", head.getCode());
            headSnapshot.put("grossPaise", applied.grossPaise());
            headSnapshot.put("discountPaise", applied.discountPaise());
            if (config != null) headSnapshot.put("configType", config.getConfigType().name());
            feeHeadSnapshots.add(headSnapshot);

            // Built from the exact applied.grossPaise()/discountPaise() values just added to
            // the running totals above — never a second, independent calculation. This is
            // what makes SUM(lineItems.netPaise) == baseAmountDue true by construction.
            lineItems.add(new LineItemSnapshot(
                    LineItemType.FEE_HEAD.name(),
                    head.getId(),
                    head.getCode(),
                    head.getName(),
                    head.getFrequency() != null ? head.getFrequency().name() : null,
                    applied.grossPaise(),
                    applied.discountPaise(),
                    config != null ? config.getConfigType().name() : null
            ));

            if (isOneTime) newlyChargedOneTime.add(head.getId());
        }

        BigDecimal busFeeDue = BigDecimal.ZERO;
        if (Boolean.TRUE.equals(takesBus) && distance != null && distance > 0) {
            busFeeDue = loadBusFee(schoolId, distance, session);
            long busFeePaise = busFeeDue.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
            if (busFeePaise > 0) {
                // Bus fee is never discounted by StudentFeeConfig today (that mechanism only
                // ever targets a FeeHead) — gross == net, discount 0. If per-student bus-fee
                // discounting is ever introduced, this is the one place to change.
                lineItems.add(new LineItemSnapshot(
                        LineItemType.BUS.name(), null, null, "Bus Fee", null,
                        busFeePaise, 0L, null
                ));
            }
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", status.name());
        snapshot.put("feeHeads", feeHeadSnapshots);
        snapshot.put("busDistance", distance);
        snapshot.put("busFeeDue", busFeeDue);
        snapshot.put("asOfDate", asOfDate.toString());
        String snapshotJson;
        try {
            snapshotJson = objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            log.warn("Could not serialize amount_rule_snapshot for student={}, month={}: {}", studentId, academicMonth, e.getMessage());
            snapshotJson = null;
        }

        return new MonthSnapshot(
                BigDecimal.valueOf(grossTotalPaise - discountTotalPaise, 2),
                busFeeDue,
                BigDecimal.valueOf(discountTotalPaise, 2),
                snapshotJson,
                newlyChargedOneTime,
                status,
                lineItems
        );
    }

    /** Prorates only genuinely recurring monthly charges and bus service for the remaining
     * calendar days, inclusive of the effective date. Fixed periodic and one-time charges
     * remain whole. Every line is rounded independently to the nearest paise (HALF_UP), then
     * parent totals are derived from those exact line values so reconciliation is exact. */
    public MonthSnapshot prorateRecurringSnapshot(MonthSnapshot source, LocalDate effectiveDate) {
        if (source == null || effectiveDate == null || effectiveDate.getDayOfMonth() <= 1) return source;
        int daysInMonth = effectiveDate.lengthOfMonth();
        int billableDays = daysInMonth - effectiveDate.getDayOfMonth() + 1;
        BigDecimal factor = BigDecimal.valueOf(billableDays)
                .divide(BigDecimal.valueOf(daysInMonth), 8, RoundingMode.HALF_UP);
        List<LineItemSnapshot> adjusted = new ArrayList<>();
        long baseNetPaise = 0L;
        long discountPaise = 0L;
        long busNetPaise = 0L;
        for (LineItemSnapshot line : source.lineItems()) {
            boolean recurring = LineItemType.BUS.name().equals(line.lineItemType())
                    || FeeFrequency.MONTHLY.name().equals(line.frequency());
            long gross = recurring ? proratePaise(line.grossPaise(), billableDays, daysInMonth) : line.grossPaise();
            long discount = recurring ? proratePaise(line.discountPaise(), billableDays, daysInMonth) : line.discountPaise();
            LineItemSnapshot value = new LineItemSnapshot(line.lineItemType(), line.feeHeadId(), line.feeHeadCode(),
                    line.feeHeadName(), line.frequency(), gross, discount, line.discountConfigType());
            adjusted.add(value);
            if (LineItemType.BUS.name().equals(value.lineItemType())) busNetPaise += value.netPaise();
            else { baseNetPaise += value.netPaise(); discountPaise += value.discountPaise(); }
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("status", source.status().name());
        context.put("sourceSnapshot", source.ruleSnapshotJson());
        context.put("prorationPolicy", "PRORATE_JOINING_MONTH");
        context.put("effectiveDate", effectiveDate.toString());
        context.put("billableDays", billableDays);
        context.put("daysInMonth", daysInMonth);
        context.put("factor", factor);
        String json;
        try { json = objectMapper.writeValueAsString(context); }
        catch (JsonProcessingException ex) { json = source.ruleSnapshotJson(); }
        return new MonthSnapshot(BigDecimal.valueOf(baseNetPaise, 2), BigDecimal.valueOf(busNetPaise, 2),
                BigDecimal.valueOf(discountPaise, 2), json, source.newlyChargedOneTimeFeeHeadIds(),
                source.status(), adjusted);
    }

    public BigDecimal prorationFactor(LocalDate effectiveDate) {
        if (effectiveDate == null || effectiveDate.getDayOfMonth() <= 1) return BigDecimal.ONE;
        return BigDecimal.valueOf(effectiveDate.lengthOfMonth() - effectiveDate.getDayOfMonth() + 1L)
                .divide(BigDecimal.valueOf(effectiveDate.lengthOfMonth()), 8, RoundingMode.HALF_UP);
    }

    private long proratePaise(long amount, int billableDays, int daysInMonth) {
        return BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(billableDays))
                .divide(BigDecimal.valueOf(daysInMonth), 0, RoundingMode.HALF_UP).longValueExact();
    }

    /**
     * Whether a StudentFees row's stored snapshot can be trusted as-is by a READING consumer
     * (payment validation, reminders, checkout) without recomputing anything. Requires BOTH a
     * non-null baseAmountDue AND a COMPUTED status — a NULL snapshotStatus (a row generated
     * before this column existed) or NO_ACTIVE_RULE_THIS_MONTH must never be treated as
     * trustworthy, even though baseAmountDue might already be a real (if stale/unreliable)
     * number in either case.
     */
    public boolean isTrustworthySnapshot(StudentFees fee) {
        return fee.getBaseAmountDue() != null && fee.getSnapshotStatus() == SnapshotStatus.COMPUTED;
    }

    /**
     * Resolves "school fee due" (base fee + bus fee, net of discount — excludes late fee and
     * platform fee, which are computed separately and never baked into this figure) for a
     * single already-generated StudentFees row.
     *
     * This is the target-rule boundary in practice: FeeCalculationService is used to CREATE a
     * charge (computeMonthSnapshot, called once at generation time) — this method is instead
     * how a consumer READS an already-created charge. It prefers the row's own stored snapshot
     * whenever isTrustworthySnapshot is true, and only falls back to a live "today's active
     * rules" recomputation (the same shape of lookup every consumer used before the snapshot
     * model existed) when no trustworthy snapshot exists — never silently, always logged.
     *
     * Returns Optional.empty() — never a fabricated ₹0 — when the amount is genuinely unknown:
     * no trustworthy snapshot AND no dynamic rule configured for the class either. Callers must
     * treat empty as "cannot determine," not "nothing owed."
     */
    public Optional<BigDecimal> resolveSchoolFeeDue(StudentFees fee, Long schoolId, String session) {
        if (isTrustworthySnapshot(fee)) {
            // baseAmountDue is written by computeMonthSnapshot as grossTotalPaise minus
            // discountTotalPaise — i.e. it is ALREADY net of discount. discountAmount is
            // stored alongside it purely for display/audit ("here's how much was taken off"),
            // not as a second deduction still owed. Subtracting it again here previously
            // double-counted every discount/waiver, undercharging by exactly the discount
            // amount a second time — found during this phase's re-audit, fixed as a
            // prerequisite for the line-item reconciliation invariant (sum of line items must
            // equal this same figure, and line items are built net-of-discount exactly once).
            BigDecimal base = fee.getBaseAmountDue();
            BigDecimal bus = fee.getBusFeeDue() != null ? fee.getBusFeeDue() : BigDecimal.ZERO;
            return Optional.of(base.add(bus));
        }

        log.warn("StudentFees id={} student={} month={} year={} has no trustworthy snapshot "
                        + "(baseAmountDue={}, snapshotStatus={}) — falling back to a live rule lookup.",
                fee.getId(), fee.getStudentId(), fee.getMonth(), fee.getYear(),
                fee.getBaseAmountDue(), fee.getSnapshotStatus());

        List<FeeStructureRule> rules = loadActiveRules(schoolId, session, fee.getClassName());
        if (rules.isEmpty()) {
            return Optional.empty();
        }
        double amount = calculateMonthFeeRupees(fee.getMonth(), rules);
        if (Boolean.TRUE.equals(fee.getTakesBus()) && fee.getDistance() != null && fee.getDistance() > 0) {
            amount += loadBusFee(schoolId, fee.getDistance(), session).doubleValue();
        }
        return Optional.of(BigDecimal.valueOf(amount));
    }
}
