package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.FeeConfigType;
import com.indraacademy.ias_management.entity.FeeFrequency;
import com.indraacademy.ias_management.entity.FeeHead;
import com.indraacademy.ias_management.entity.FeeStructureRule;
import com.indraacademy.ias_management.entity.SnapshotStatus;
import com.indraacademy.ias_management.entity.StudentFeeConfig;
import com.indraacademy.ias_management.entity.StudentFees;
import com.indraacademy.ias_management.repository.AcademicSessionRepository;
import com.indraacademy.ias_management.repository.BusFeesRepository;
import com.indraacademy.ias_management.repository.FeeStructureRuleRepository;
import com.indraacademy.ias_management.repository.StudentFeeConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Sub-phase 1a coverage: the asOf-dated rule lookup, the mid-session-join frequency
 * predicate, the relocated academic-calendar helpers, and StudentFeeConfig application.
 * Sub-phase 1b coverage: computeMonthSnapshot, the actual per-row snapshot calculation
 * StudentFeesGenerationService and StudentFeesService.createDefaultStudentFees are built on.
 */
@ExtendWith(MockitoExtension.class)
class FeeCalculationServiceTest {

    @Mock private FeeStructureRuleRepository feeStructureRuleRepository;
    @Mock private AcademicSessionRepository academicSessionRepository;
    @Mock private BusFeesRepository busFeesRepository;
    @Mock private StudentFeeConfigRepository studentFeeConfigRepository;

    private FeeCalculationService service;

    private static final Long SCHOOL_ID = 1L;
    private static final String SESSION = "2025-2026";
    private static final String CLASS_NAME = "5A";
    private static final String STUDENT_ID = "S1";
    private static final LocalDate AS_OF = LocalDate.of(2025, 9, 1);

    @BeforeEach
    void setUp() {
        service = new FeeCalculationService();
        ReflectionTestUtils.setField(service, "feeStructureRuleRepository", feeStructureRuleRepository);
        ReflectionTestUtils.setField(service, "academicSessionRepository", academicSessionRepository);
        ReflectionTestUtils.setField(service, "busFeesRepository", busFeesRepository);
        ReflectionTestUtils.setField(service, "studentFeeConfigRepository", studentFeeConfigRepository);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        AcademicSession session = new AcademicSession();
        session.setId(42L);
        session.setLabel(SESSION);
        lenient().when(academicSessionRepository.findBySchoolIdAndLabel(SCHOOL_ID, SESSION)).thenReturn(Optional.of(session));
        lenient().when(studentFeeConfigRepository.findActiveConfigs(eq(SCHOOL_ID), eq(STUDENT_ID), eq(42L), any()))
                .thenReturn(List.of());
    }

    private FeeHead feeHead(FeeFrequency frequency, String dueMonthsJson) {
        FeeHead head = new FeeHead();
        head.setFrequency(frequency);
        head.setDueMonths(dueMonthsJson);
        head.setActive(true);
        return head;
    }

    private FeeHead feeHead(Long id, String code, FeeFrequency frequency, String dueMonthsJson) {
        FeeHead head = feeHead(frequency, dueMonthsJson);
        head.setId(id);
        head.setCode(code);
        return head;
    }

    private FeeStructureRule feeRule(FeeHead head, long amountPaise) {
        FeeStructureRule rule = new FeeStructureRule();
        rule.setFeeHead(head);
        rule.setAmount(amountPaise);
        return rule;
    }

    private StudentFeeConfig studentFeeConfig(FeeHead head, FeeConfigType type, BigDecimal value) {
        StudentFeeConfig config = new StudentFeeConfig();
        config.setFeeHead(head);
        config.setConfigType(type);
        config.setValue(value);
        return config;
    }

    private void stubRules(FeeStructureRule... rules) {
        when(feeStructureRuleRepository.findActiveRules(eq(SCHOOL_ID), eq(42L), eq(CLASS_NAME), any()))
                .thenReturn(List.of(rules));
    }

    private void stubConfigs(StudentFeeConfig... configs) {
        when(studentFeeConfigRepository.findActiveConfigs(eq(SCHOOL_ID), eq(STUDENT_ID), eq(42L), any()))
                .thenReturn(List.of(configs));
    }

    private static final String ALL_12 = "[1,2,3,4,5,6,7,8,9,10,11,12]";

    // ─── appliesThisAcademicMonth (unchanged, but re-asserted so 1b can rely on it) ──────

    @Test
    void monthlyFeeAppliesEveryAcademicMonth() {
        FeeHead head = feeHead(FeeFrequency.MONTHLY, ALL_12);
        for (int m = 1; m <= 12; m++) {
            assertThat(service.appliesThisAcademicMonth(m, head)).as("month %d", m).isTrue();
        }
    }

    @Test
    void quarterlyFeeAppliesOnlyAtQuarterStarts() {
        FeeHead head = feeHead(FeeFrequency.QUARTERLY, ALL_12);
        assertThat(service.appliesThisAcademicMonth(1, head)).isTrue();
        assertThat(service.appliesThisAcademicMonth(4, head)).isTrue();
        assertThat(service.appliesThisAcademicMonth(7, head)).isTrue();
        assertThat(service.appliesThisAcademicMonth(10, head)).isTrue();
        assertThat(service.appliesThisAcademicMonth(2, head)).isFalse();
        assertThat(service.appliesThisAcademicMonth(5, head)).isFalse();
    }

    @Test
    void annualFeeAppliesOnlyInFirstAcademicMonth() {
        FeeHead head = feeHead(FeeFrequency.ANNUAL, ALL_12);
        assertThat(service.appliesThisAcademicMonth(1, head)).isTrue();
        for (int m = 2; m <= 12; m++) {
            assertThat(service.appliesThisAcademicMonth(m, head)).as("month %d", m).isFalse();
        }
    }

    // ─── appliesAtJoin (new — mid-session admission policy) ──────────────────────────────

    @Test
    void annualFeeAppliesAtJoinRegardlessOfMonth() {
        FeeHead head = feeHead(FeeFrequency.ANNUAL, ALL_12);
        for (int joinMonth = 1; joinMonth <= 12; joinMonth++) {
            assertThat(service.appliesAtJoin(joinMonth, head)).as("join month %d", joinMonth).isTrue();
        }
    }

    @Test
    void oneTimeFeeAppliesAtJoinRegardlessOfMonth() {
        FeeHead head = feeHead(FeeFrequency.ONE_TIME, ALL_12);
        assertThat(service.appliesAtJoin(6, head)).isTrue();
    }

    @Test
    void quarterlyFeeAppliesAtJoinEvenMidPeriod() {
        // today's (pre-redesign) accidental behavior silently drops this charge entirely —
        // the new policy always charges the period a mid-session joiner lands in.
        FeeHead head = feeHead(FeeFrequency.QUARTERLY, ALL_12);
        assertThat(service.appliesAtJoin(2, head)).isTrue();  // mid-quarter, not a due month
        assertThat(service.appliesAtJoin(4, head)).isTrue();  // exactly a due month
    }

    @Test
    void monthlyFeeAppliesAtJoinAnyMonth() {
        FeeHead head = feeHead(FeeFrequency.MONTHLY, ALL_12);
        assertThat(service.appliesAtJoin(8, head)).isTrue();
    }

    // ─── appliesAtJoin with a genuinely CUSTOM dueMonths list (academic-calendar audit) ───

    @Test
    void customDueMonths_chargesAtJoinWhenAPeriodHasAlreadyStarted() {
        // Admin configured a custom (non-frequency-default) schedule: due at academic
        // months 2 and 8 only. A student joining at month 5 has missed month 2's charge —
        // must be caught up at join, per the admin's actual configured schedule.
        FeeHead head = feeHead(FeeFrequency.QUARTERLY, "[2,8]");
        assertThat(service.appliesAtJoin(5, head)).isTrue();
    }

    @Test
    void customDueMonths_doesNotChargeAtJoinWhenNoPeriodHasStartedYet() {
        // Same custom schedule (due at months 2 and 8). A student joining at month 1 —
        // before either configured due-month — has nothing to catch up on; the normal
        // schedule will charge them at month 2 naturally. Must NOT be charged at join.
        FeeHead head = feeHead(FeeFrequency.QUARTERLY, "[2,8]");
        assertThat(service.appliesAtJoin(1, head)).isFalse();
    }

    @Test
    void customDueMonths_exactlyOnADueMonthStillApplies() {
        FeeHead head = feeHead(FeeFrequency.QUARTERLY, "[2,8]");
        assertThat(service.appliesAtJoin(2, head)).isTrue();
    }

    // ─── getMonthName — session-start-month-agnostic display names (academic-calendar audit) ─

    @Test
    void getMonthName_aprilStartSchool() {
        assertThat(service.getMonthName(1, 4)).isEqualTo("April");
        assertThat(service.getMonthName(9, 4)).isEqualTo("December");
        assertThat(service.getMonthName(10, 4)).isEqualTo("January");
        assertThat(service.getMonthName(12, 4)).isEqualTo("March");
    }

    @Test
    void getMonthName_januaryStartSchool() {
        assertThat(service.getMonthName(1, 1)).isEqualTo("January");
        assertThat(service.getMonthName(12, 1)).isEqualTo("December");
    }

    @Test
    void getMonthName_julyStartSchool() {
        assertThat(service.getMonthName(1, 7)).isEqualTo("July");
        assertThat(service.getMonthName(6, 7)).isEqualTo("December");
        assertThat(service.getMonthName(7, 7)).isEqualTo("January");
    }

    @Test
    void getMonthName_decemberStartSchool() {
        assertThat(service.getMonthName(1, 12)).isEqualTo("December");
        assertThat(service.getMonthName(2, 12)).isEqualTo("January");
        assertThat(service.getMonthName(12, 12)).isEqualTo("November");
    }

    @Test
    void getMonthName_outOfRangeIsUnknownNotACrash() {
        assertThat(service.getMonthName(0, 4)).isEqualTo("Unknown");
        assertThat(service.getMonthName(13, 4)).isEqualTo("Unknown");
    }

    // ─── academicMonthStart / parseSession (relocated from FeeReminderService) ───────────

    @Test
    void academicMonthStartResolvesAprilStartSchoolCorrectly() {
        // April-start school: academic month 1 = April, month 10 = January (next calendar year)
        assertThat(service.academicMonthStart(1, 2025, 2026, 4)).isEqualTo(LocalDate.of(2025, 4, 1));
        assertThat(service.academicMonthStart(10, 2025, 2026, 4)).isEqualTo(LocalDate.of(2026, 1, 1));
    }

    @Test
    void academicMonthStartResolvesJanuaryStartSchoolCorrectly() {
        // January-start school: academic month 1 = January, month 12 = December, same year
        assertThat(service.academicMonthStart(1, 2025, 2026, 1)).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(service.academicMonthStart(12, 2025, 2026, 1)).isEqualTo(LocalDate.of(2025, 12, 1));
    }

    @Test
    void prorateRecurringSnapshot_proratesMonthlyAndBusButKeepsQuarterlyChargeWhole() {
        FeeCalculationService.MonthSnapshot source = new FeeCalculationService.MonthSnapshot(
                new BigDecimal("1179.00"), new BigDecimal("310.00"), new BigDecimal("31.00"),
                "{}", List.of(), SnapshotStatus.COMPUTED,
                List.of(
                        new FeeCalculationService.LineItemSnapshot("FEE_HEAD", 1L, "TUITION", "Tuition",
                                "MONTHLY", 31000L, 3100L, null),
                        new FeeCalculationService.LineItemSnapshot("FEE_HEAD", 2L, "TERM", "Term Fee",
                                "QUARTERLY", 90000L, 0L, null),
                        new FeeCalculationService.LineItemSnapshot("BUS", null, null, "Bus Fee",
                                null, 31000L, 0L, null)));

        FeeCalculationService.MonthSnapshot result = service.prorateRecurringSnapshot(
                source, LocalDate.of(2026, 8, 16));

        assertThat(result.baseAmountDue()).isEqualByComparingTo("1044.00");
        assertThat(result.busFeeDue()).isEqualByComparingTo("160.00");
        assertThat(result.discountAmount()).isEqualByComparingTo("16.00");
        assertThat(result.lineItems()).extracting(FeeCalculationService.LineItemSnapshot::grossPaise)
                .containsExactly(16000L, 90000L, 16000L);
        assertThat(service.prorationFactor(LocalDate.of(2026, 8, 16)))
                .isEqualByComparingTo("0.51612903");
    }

    @Test
    void parseSessionSplitsLabelIntoStartAndEndYear() {
        assertThat(service.parseSession("2025-2026")).containsExactly(2025, 2026);
    }

    // ─── loadActiveRules(asOf) wiring — the actual fix for F6 / this phase's core promise ─

    @Test
    void loadActiveRulesPassesGivenAsOfDateThroughToRepository() {
        Long schoolId = 1L;
        String session = "2025-2026";
        String className = "5A";
        LocalDate asOf = LocalDate.of(2025, 8, 15);

        AcademicSession sessionEntity = new AcademicSession();
        sessionEntity.setId(42L);
        when(academicSessionRepository.findBySchoolIdAndLabel(schoolId, session)).thenReturn(Optional.of(sessionEntity));

        service.loadActiveRules(schoolId, session, className, asOf);

        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(feeStructureRuleRepository).findActiveRules(eq(schoolId), eq(42L), eq(className), dateCaptor.capture());
        assertThat(dateCaptor.getValue()).isEqualTo(asOf);
    }

    @Test
    void loadActiveRulesWithoutExplicitDateDefaultsToToday() {
        Long schoolId = 1L;
        String session = "2025-2026";
        String className = "5A";

        AcademicSession sessionEntity = new AcademicSession();
        sessionEntity.setId(42L);
        when(academicSessionRepository.findBySchoolIdAndLabel(schoolId, session)).thenReturn(Optional.of(sessionEntity));

        service.loadActiveRules(schoolId, session, className);

        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(feeStructureRuleRepository).findActiveRules(eq(schoolId), eq(42L), eq(className), dateCaptor.capture());
        assertThat(dateCaptor.getValue()).isEqualTo(LocalDate.now());
    }

    // ─── bus fee lookup — schoolId-explicit, safe for batch/scheduled generation ─────────

    @Test
    void loadBusFeeReturnsZeroNotNullWhenNoBracketMatches() {
        when(busFeesRepository.findFeesByDistanceAndAcademicYearAndSchoolId(any(), any(), any())).thenReturn(null);
        assertThat(service.loadBusFee(1L, 12.0, "2025-2026")).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void loadBusFeeReturnsZeroWhenDistanceIsNull() {
        assertThat(service.loadBusFee(1L, null, "2025-2026")).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ─── StudentFeeConfig application — mirrors InvoiceGenerationService's switch exactly ─

    private StudentFeeConfig config(FeeConfigType type, BigDecimal value) {
        StudentFeeConfig c = new StudentFeeConfig();
        c.setConfigType(type);
        c.setValue(value);
        return c;
    }

    @Test
    void waiverZeroesOutTheFullAmount() {
        var result = service.applyStudentFeeConfig(150_000L, config(FeeConfigType.WAIVER, null));
        assertThat(result.grossPaise()).isEqualTo(150_000L);
        assertThat(result.discountPaise()).isEqualTo(150_000L);
        assertThat(result.netPaise()).isZero();
    }

    @Test
    void discountPercentRoundsHalfUp() {
        var result = service.applyStudentFeeConfig(100_000L, config(FeeConfigType.DISCOUNT_PERCENT, BigDecimal.valueOf(10)));
        assertThat(result.discountPaise()).isEqualTo(10_000L);
        assertThat(result.netPaise()).isEqualTo(90_000L);
    }

    @Test
    void discountFixedNeverExceedsTheBaseAmount() {
        var result = service.applyStudentFeeConfig(5_000L, config(FeeConfigType.DISCOUNT_FIXED, BigDecimal.valueOf(20_000)));
        assertThat(result.discountPaise()).isEqualTo(5_000L);
        assertThat(result.netPaise()).isZero();
    }

    @Test
    void customAmountOverridesTheRuleAmountEntirely() {
        var result = service.applyStudentFeeConfig(150_000L, config(FeeConfigType.CUSTOM_AMOUNT, BigDecimal.valueOf(75_000)));
        assertThat(result.grossPaise()).isEqualTo(75_000L);
        assertThat(result.discountPaise()).isZero();
        assertThat(result.netPaise()).isEqualTo(75_000L);
    }

    @Test
    void optOutZeroesBothGrossAndDiscount() {
        var result = service.applyStudentFeeConfig(150_000L, config(FeeConfigType.OPT_OUT, null));
        assertThat(result.grossPaise()).isZero();
        assertThat(result.discountPaise()).isZero();
    }

    @Test
    void noConfigLeavesTheRuleAmountUntouched() {
        var result = service.applyStudentFeeConfig(150_000L, null);
        assertThat(result.grossPaise()).isEqualTo(150_000L);
        assertThat(result.discountPaise()).isZero();
        assertThat(result.netPaise()).isEqualTo(150_000L);
    }

    // ─── computeMonthSnapshot — sub-phase 1b's actual per-row snapshot calculation ────────

    private FeeCalculationService.MonthSnapshot snapshot(int academicMonth, boolean isFirstRow, Set<Long> alreadyCharged) {
        return service.computeMonthSnapshot(SCHOOL_ID, SESSION, CLASS_NAME, STUDENT_ID,
                academicMonth, isFirstRow, AS_OF, false, null, alreadyCharged);
    }

    private FeeCalculationService.MonthSnapshot snapshotWithBus(int academicMonth, boolean isFirstRow,
                                                                  Boolean takesBus, Double distance, Set<Long> alreadyCharged) {
        return service.computeMonthSnapshot(SCHOOL_ID, SESSION, CLASS_NAME, STUDENT_ID,
                academicMonth, isFirstRow, AS_OF, takesBus, distance, alreadyCharged);
    }

    @Test
    void monthly_appliesEveryMonthForAContinuingStudent() {
        FeeHead tuition = feeHead(1L, "TUITION", FeeFrequency.MONTHLY, ALL_12);
        stubRules(feeRule(tuition, 200_000L)); // Rs 2000
        stubConfigs();

        var s = snapshot(5, false, Set.of());
        assertThat(s.baseAmountDue()).isEqualByComparingTo("2000.00");
        assertThat(s.discountAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void quarterly_onlyChargesAtQuarterStartsForAContinuingStudent() {
        FeeHead lab = feeHead(2L, "LAB", FeeFrequency.QUARTERLY, ALL_12);
        stubRules(feeRule(lab, 60_000L)); // Rs 600
        stubConfigs();

        assertThat(snapshot(4, false, Set.of()).baseAmountDue()).isEqualByComparingTo("600.00");
        assertThat(snapshot(5, false, Set.of()).baseAmountDue()).isEqualByComparingTo("0.00");
    }

    @Test
    void semiAnnual_chargesOnlyAtMonths1And7ForAContinuingStudent() {
        FeeHead head = feeHead(3L, "SEMI", FeeFrequency.SEMI_ANNUAL, ALL_12);
        stubRules(feeRule(head, 300_000L));
        stubConfigs();

        assertThat(snapshot(1, false, Set.of()).baseAmountDue()).isEqualByComparingTo("3000.00");
        assertThat(snapshot(7, false, Set.of()).baseAmountDue()).isEqualByComparingTo("3000.00");
        assertThat(snapshot(4, false, Set.of()).baseAmountDue()).isEqualByComparingTo("0.00");
    }

    @Test
    void annual_continuingStudentChargedOnlyAtMonth1() {
        FeeHead head = feeHead(4L, "ANNUAL_CHARGES", FeeFrequency.ANNUAL, ALL_12);
        stubRules(feeRule(head, 500_000L));
        stubConfigs();

        assertThat(snapshot(1, false, Set.of()).baseAmountDue()).isEqualByComparingTo("5000.00");
        assertThat(snapshot(6, false, Set.of()).baseAmountDue()).isEqualByComparingTo("0.00");
    }

    @Test
    void annual_midSessionJoinerChargedInFullAtTheirFirstRowNotMonth1() {
        // Approved mid-session admission policy: ANNUAL charges in full at the student's
        // own first generated row, whatever academic month that is — never silently skipped.
        FeeHead head = feeHead(4L, "ANNUAL_CHARGES", FeeFrequency.ANNUAL, ALL_12);
        stubRules(feeRule(head, 500_000L));
        stubConfigs();

        // Joins at academic month 6 — isFirstRow=true, NOT month 1.
        assertThat(snapshot(6, true, Set.of()).baseAmountDue()).isEqualByComparingTo("5000.00");
    }

    @Test
    void quarterly_midSessionJoinerGetsCatchUpChargeMidPeriod() {
        // Approved policy: the current in-progress quarter is charged in full at admission,
        // even off a due-month, instead of silently dropped.
        FeeHead lab = feeHead(2L, "LAB", FeeFrequency.QUARTERLY, ALL_12);
        stubRules(feeRule(lab, 60_000L));
        stubConfigs();

        assertThat(snapshot(5, true, Set.of()).baseAmountDue()).isEqualByComparingTo("600.00"); // catch-up
        assertThat(snapshot(7, false, Set.of()).baseAmountDue()).isEqualByComparingTo("600.00"); // next quarter, normal
    }

    @Test
    void oneTime_chargedOnceForANewStudentAtTheirFirstRow() {
        FeeHead admission = feeHead(5L, "ADMISSION", FeeFrequency.ONE_TIME, ALL_12);
        stubRules(feeRule(admission, 1_000_000L)); // Rs 10,000
        stubConfigs();

        var s = snapshot(6, true, Set.of());
        assertThat(s.baseAmountDue()).isEqualByComparingTo("10000.00");
        assertThat(s.newlyChargedOneTimeFeeHeadIds()).containsExactly(5L);
    }

    @Test
    void oneTime_notChargedAgainWhenAlreadyInTheDedupSet() {
        // Simulates a continuing student rolling into a new session: the ONE_TIME head was
        // already charged (and recorded) in a prior session.
        FeeHead admission = feeHead(5L, "ADMISSION", FeeFrequency.ONE_TIME, ALL_12);
        stubRules(feeRule(admission, 1_000_000L));
        stubConfigs();

        var s = snapshot(1, false, Set.of(5L));
        assertThat(s.baseAmountDue()).isEqualByComparingTo("0.00");
        assertThat(s.newlyChargedOneTimeFeeHeadIds()).isEmpty();
    }

    @Test
    void customDueMonths_respectedExactlyAsConfigured() {
        FeeHead head = feeHead(6L, "CUSTOM", FeeFrequency.QUARTERLY, "[2,8]");
        stubRules(feeRule(head, 40_000L));
        stubConfigs();

        assertThat(snapshot(2, false, Set.of()).baseAmountDue()).isEqualByComparingTo("400.00");
        assertThat(snapshot(3, false, Set.of()).baseAmountDue()).isEqualByComparingTo("0.00");
        // Mid-session joiner at month 5 (between the two custom due-months) catches up on month 2.
        assertThat(snapshot(5, true, Set.of()).baseAmountDue()).isEqualByComparingTo("400.00");
        // Joining at month 1 (before either custom due-month) has nothing to catch up on.
        assertThat(snapshot(1, true, Set.of()).baseAmountDue()).isEqualByComparingTo("0.00");
    }

    @Test
    void discountPercent_appliedToTheSnapshotAndRecorded() {
        FeeHead tuition = feeHead(1L, "TUITION", FeeFrequency.MONTHLY, ALL_12);
        stubRules(feeRule(tuition, 200_000L));
        stubConfigs(studentFeeConfig(tuition, FeeConfigType.DISCOUNT_PERCENT, BigDecimal.valueOf(20)));

        var s = snapshot(3, false, Set.of());
        assertThat(s.baseAmountDue()).isEqualByComparingTo("1600.00");
        assertThat(s.discountAmount()).isEqualByComparingTo("400.00");
    }

    @Test
    void waiver_zeroesTheFeeHeadEntirely() {
        FeeHead tuition = feeHead(1L, "TUITION", FeeFrequency.MONTHLY, ALL_12);
        stubRules(feeRule(tuition, 200_000L));
        stubConfigs(studentFeeConfig(tuition, FeeConfigType.WAIVER, null));

        var s = snapshot(3, false, Set.of());
        assertThat(s.baseAmountDue()).isEqualByComparingTo("0.00");
        assertThat(s.discountAmount()).isEqualByComparingTo("2000.00");
    }

    @Test
    void customAmount_overridesTheRulePriceInTheSnapshot() {
        FeeHead tuition = feeHead(1L, "TUITION", FeeFrequency.MONTHLY, ALL_12);
        stubRules(feeRule(tuition, 200_000L));
        stubConfigs(studentFeeConfig(tuition, FeeConfigType.CUSTOM_AMOUNT, BigDecimal.valueOf(150_000)));

        var s = snapshot(3, false, Set.of());
        assertThat(s.baseAmountDue()).isEqualByComparingTo("1500.00");
        assertThat(s.discountAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void optOut_excludesAnOptionalFeeHeadEntirely() {
        FeeHead eca = feeHead(7L, "ECA", FeeFrequency.MONTHLY, ALL_12);
        eca.setOptional(true);
        stubRules(feeRule(eca, 50_000L));
        stubConfigs(studentFeeConfig(eca, FeeConfigType.OPT_OUT, null));

        var s = snapshot(3, false, Set.of());
        assertThat(s.baseAmountDue()).isEqualByComparingTo("0.00");
        assertThat(s.discountAmount()).isEqualByComparingTo("0.00"); // excluded, not "discounted"
    }

    @Test
    void busFee_addedWhenStudentTakesBusWithARealDistance() {
        stubRules();
        stubConfigs();
        when(busFeesRepository.findFeesByDistanceAndAcademicYearAndSchoolId(12.0, SESSION, SCHOOL_ID))
                .thenReturn(BigDecimal.valueOf(800));

        var s = snapshotWithBus(3, false, true, 12.0, Set.of());
        assertThat(s.busFeeDue()).isEqualByComparingTo("800.00");
    }

    @Test
    void busFee_zeroWhenStudentDoesNotTakeBus() {
        stubRules();
        stubConfigs();

        var s = snapshotWithBus(3, false, false, 12.0, Set.of());
        assertThat(s.busFeeDue()).isEqualByComparingTo("0.00");
    }

    @Test
    void busFee_studentJoiningBusLater_noBusChargeUntilThen() {
        // Sub-phase 1b's generation-time behavior: bus_fee_due reflects whatever takesBus
        // flag is passed in at generation for THAT row. A student who doesn't take the bus
        // yet gets 0 for their early rows; a later, explicit bus-assignment change (with its
        // own audit trail) is sub-phase 1f's job, not this one.
        stubRules();
        stubConfigs();
        var beforeBus = snapshotWithBus(6, true, false, null, Set.of());
        assertThat(beforeBus.busFeeDue()).isEqualByComparingTo("0.00");

        when(busFeesRepository.findFeesByDistanceAndAcademicYearAndSchoolId(9.0, SESSION, SCHOOL_ID))
                .thenReturn(BigDecimal.valueOf(650));
        var afterBus = snapshotWithBus(7, false, true, 9.0, Set.of());
        assertThat(afterBus.busFeeDue()).isEqualByComparingTo("650.00");
    }

    @Test
    void ruleChangeAfterSnapshotComputed_doesNotRetroactivelyChangeAnAlreadyReturnedSnapshot() {
        // The snapshot is a plain computed value (BigDecimal), not a live reference back to
        // the rule — recomputing with a different mocked rule state produces a NEW, separate
        // snapshot; it never mutates one already handed back to a caller. This is the
        // property that lets StudentFeesGenerationService/StudentFeesService persist the
        // first result permanently once written to a StudentFees row.
        FeeHead tuition = feeHead(1L, "TUITION", FeeFrequency.MONTHLY, ALL_12);
        stubRules(feeRule(tuition, 200_000L));
        stubConfigs();

        var originalSnapshot = snapshot(3, false, Set.of());
        assertThat(originalSnapshot.baseAmountDue()).isEqualByComparingTo("2000.00");

        // Admin changes the price — re-stub to a different rule amount.
        stubRules(feeRule(tuition, 300_000L));
        var newSnapshot = snapshot(3, false, Set.of());

        assertThat(newSnapshot.baseAmountDue()).isEqualByComparingTo("3000.00");
        // The ORIGINAL snapshot object, already handed back (and, in real generation, already
        // written into a StudentFees row), is completely unaffected by the later rule change.
        assertThat(originalSnapshot.baseAmountDue()).isEqualByComparingTo("2000.00");
    }

    // ─── Hardening: distinguishing confident zero from "we cannot calculate this" ────────

    @Test
    void validateFeeConfiguration_failsWhenAcademicSessionIsMissing() {
        when(academicSessionRepository.findBySchoolIdAndLabel(SCHOOL_ID, SESSION)).thenReturn(Optional.empty());

        var result = service.validateFeeConfiguration(SCHOOL_ID, SESSION, CLASS_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("AcademicSession").contains(SESSION);
    }

    @Test
    void validateFeeConfiguration_failsWhenNoRuleHasEverBeenConfiguredForTheClass() {
        when(feeStructureRuleRepository.findBySchoolIdAndAcademicSessionIdAndClassName(SCHOOL_ID, 42L, CLASS_NAME))
                .thenReturn(List.of());

        var result = service.validateFeeConfiguration(SCHOOL_ID, SESSION, CLASS_NAME);

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("FeeStructureRule").contains(CLASS_NAME);
    }

    @Test
    void validateFeeConfiguration_succeedsWhenSessionExistsAndAtLeastOneRuleWasEverConfigured() {
        FeeHead tuition = feeHead(1L, "TUITION", FeeFrequency.MONTHLY, ALL_12);
        when(feeStructureRuleRepository.findBySchoolIdAndAcademicSessionIdAndClassName(SCHOOL_ID, 42L, CLASS_NAME))
                .thenReturn(List.of(feeRule(tuition, 200_000L)));

        var result = service.validateFeeConfiguration(SCHOOL_ID, SESSION, CLASS_NAME);

        assertThat(result.valid()).isTrue();
        assertThat(result.reason()).isNull();
    }

    @Test
    void computeMonthSnapshot_missingSession_flagsNoActiveRuleRatherThanAConfidentZero() {
        // Defensive fallback for computeMonthSnapshot called directly without the caller
        // having run validateFeeConfiguration first (the real callers always do — this just
        // proves the method itself never silently reports a confident zero for a session it
        // couldn't even find).
        when(academicSessionRepository.findBySchoolIdAndLabel(SCHOOL_ID, SESSION)).thenReturn(Optional.empty());

        var s = snapshot(3, false, Set.of());

        assertThat(s.status()).isEqualTo(SnapshotStatus.NO_ACTIVE_RULE_THIS_MONTH);
        assertThat(s.baseAmountDue()).isEqualByComparingTo("0.00");
        assertThat(s.ruleSnapshotJson()).contains("NO_ACTIVE_RULE_THIS_MONTH");
    }

    @Test
    void computeMonthSnapshot_noRuleActiveThisSpecificMonth_flagsNoActiveRuleNotConfidentZero() {
        // Session exists, but no rule is active as of this specific date (e.g. a
        // configuration gap between two rule versions) — must NOT be reported the same way
        // as a genuinely computed, confident zero.
        stubRules(); // empty — nothing active as of AS_OF
        stubConfigs();

        var s = snapshot(3, false, Set.of());

        assertThat(s.status()).isEqualTo(SnapshotStatus.NO_ACTIVE_RULE_THIS_MONTH);
        assertThat(s.baseAmountDue()).isEqualByComparingTo("0.00");
    }

    @Test
    void computeMonthSnapshot_legitimateZero_offScheduleMonth_isConfidentlyComputed() {
        // Rules ARE active and were evaluated; this QUARTERLY head just isn't due this
        // particular month. amount == 0, but status must say COMPUTED, not "unknown."
        FeeHead lab = feeHead(2L, "LAB", FeeFrequency.QUARTERLY, ALL_12);
        stubRules(feeRule(lab, 60_000L));
        stubConfigs();

        var s = snapshot(5, false, Set.of()); // month 5 is not a quarter start

        assertThat(s.status()).isEqualTo(SnapshotStatus.COMPUTED);
        assertThat(s.baseAmountDue()).isEqualByComparingTo("0.00");
    }

    @Test
    void computeMonthSnapshot_legitimateZero_fullWaiver_isConfidentlyComputed() {
        FeeHead tuition = feeHead(1L, "TUITION", FeeFrequency.MONTHLY, ALL_12);
        stubRules(feeRule(tuition, 200_000L));
        stubConfigs(studentFeeConfig(tuition, FeeConfigType.WAIVER, null));

        var s = snapshot(3, false, Set.of());

        assertThat(s.status()).isEqualTo(SnapshotStatus.COMPUTED);
        assertThat(s.baseAmountDue()).isEqualByComparingTo("0.00");
        assertThat(s.discountAmount()).isEqualByComparingTo("2000.00"); // proves it was a waiver, not "nothing found"
    }

    @Test
    void computeMonthSnapshot_partiallyConfiguredFeeStructure_someHeadsPricedSomeNot_stillComputesConfidently() {
        // Only TUITION has a rule for this class; LAB was never configured at all. This is a
        // completely normal, legitimate partial fee structure (e.g. a school with no lab
        // fee for this class) — must compute confidently from whatever rules DO exist, not
        // be treated as an error.
        FeeHead tuition = feeHead(1L, "TUITION", FeeFrequency.MONTHLY, ALL_12);
        stubRules(feeRule(tuition, 200_000L)); // only one rule, no LAB rule at all
        stubConfigs();

        var s = snapshot(3, false, Set.of());

        assertThat(s.status()).isEqualTo(SnapshotStatus.COMPUTED);
        assertThat(s.baseAmountDue()).isEqualByComparingTo("2000.00"); // just TUITION, correctly
    }

    @Test
    void computeMonthSnapshot_partiallyConfiguredFeeStructure_oneMonthGapAmongOtherwiseCoveredMonths() {
        // A rule genuinely exists for the class (so validateFeeConfiguration would pass),
        // but at THIS specific asOf date none are active (e.g. an effectiveUntil boundary) —
        // while a sibling month, evaluated separately, IS covered. Each month is judged on
        // its own asOf-dated result, not on whether the class has "some" configuration.
        FeeHead tuition = feeHead(1L, "TUITION", FeeFrequency.MONTHLY, ALL_12);

        stubRules(); // this month: gap, nothing active
        stubConfigs();
        var gapMonth = snapshot(3, false, Set.of());
        assertThat(gapMonth.status()).isEqualTo(SnapshotStatus.NO_ACTIVE_RULE_THIS_MONTH);

        stubRules(feeRule(tuition, 200_000L)); // sibling month: covered
        var coveredMonth = snapshot(4, false, Set.of());
        assertThat(coveredMonth.status()).isEqualTo(SnapshotStatus.COMPUTED);
        assertThat(coveredMonth.baseAmountDue()).isEqualByComparingTo("2000.00");
    }

    // ─── isTrustworthySnapshot / resolveSchoolFeeDue — the read-time consumer migration ────

    private StudentFees studentFeesRow(BigDecimal baseAmountDue, BigDecimal busFeeDue, BigDecimal discountAmount,
                                        SnapshotStatus status) {
        StudentFees fee = new StudentFees();
        fee.setId(99L);
        fee.setStudentId(STUDENT_ID);
        fee.setClassName(CLASS_NAME);
        fee.setMonth(3);
        fee.setYear(SESSION);
        fee.setBaseAmountDue(baseAmountDue);
        fee.setBusFeeDue(busFeeDue);
        fee.setDiscountAmount(discountAmount);
        fee.setSnapshotStatus(status);
        return fee;
    }

    @Test
    void isTrustworthySnapshot_trueOnlyWhenBaseAmountDuePresentAndStatusComputed() {
        assertThat(service.isTrustworthySnapshot(
                studentFeesRow(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, SnapshotStatus.COMPUTED))).isTrue();
        assertThat(service.isTrustworthySnapshot(
                studentFeesRow(null, null, null, null))).isFalse();
        assertThat(service.isTrustworthySnapshot(
                studentFeesRow(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, SnapshotStatus.NO_ACTIVE_RULE_THIS_MONTH))).isFalse();
        assertThat(service.isTrustworthySnapshot(
                studentFeesRow(BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, null))).isFalse();
    }

    @Test
    void resolveSchoolFeeDue_trustworthySnapshot_readsStoredFieldsDirectly_neverRecomputes() {
        // baseAmountDue is ALREADY net of discountAmount (that's how computeMonthSnapshot
        // writes it: grossTotalPaise - discountTotalPaise) — discountAmount is stored
        // alongside purely for display/audit, not as a second deduction. resolveSchoolFeeDue
        // must add only base + bus, never subtract discount again (a real bug this phase's
        // re-audit found and fixed — see FeeCalculationService.resolveSchoolFeeDue).
        StudentFees fee = studentFeesRow(
                BigDecimal.valueOf(2000), BigDecimal.valueOf(800), BigDecimal.valueOf(200), SnapshotStatus.COMPUTED);

        var result = service.resolveSchoolFeeDue(fee, SCHOOL_ID, SESSION);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualByComparingTo("2800.00"); // 2000 (already net) + 800 bus
        // No rule/config lookups should happen at all when the snapshot is trustworthy.
        verifyNoInteractions(feeStructureRuleRepository);
    }

    @Test
    void resolveSchoolFeeDue_missingSnapshot_fallsBackToLiveRuleLookup_logsFallback() {
        StudentFees fee = studentFeesRow(null, null, null, null); // pre-migration legacy row
        FeeHead tuition = feeHead(1L, "TUITION", FeeFrequency.MONTHLY, ALL_12);
        stubRules(feeRule(tuition, 200_000L));

        var result = service.resolveSchoolFeeDue(fee, SCHOOL_ID, SESSION);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualByComparingTo("2000.00");
    }

    @Test
    void resolveSchoolFeeDue_noActiveRuleThisMonthStatus_isTreatedAsUntrustworthy_fallsBackLive() {
        // baseAmountDue is a real (non-null) 0.00 here, but status says it's a configuration
        // gap, not a confident zero — must NOT be trusted as-is.
        StudentFees fee = studentFeesRow(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                SnapshotStatus.NO_ACTIVE_RULE_THIS_MONTH);
        FeeHead tuition = feeHead(1L, "TUITION", FeeFrequency.MONTHLY, ALL_12);
        stubRules(feeRule(tuition, 150_000L));

        var result = service.resolveSchoolFeeDue(fee, SCHOOL_ID, SESSION);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualByComparingTo("1500.00"); // live-recomputed, not the stale stored 0.00
    }

    @Test
    void resolveSchoolFeeDue_noSnapshotAndNoLiveRulesEither_returnsEmptyNeverZero() {
        StudentFees fee = studentFeesRow(null, null, null, null);
        stubRules(); // nothing configured live either

        var result = service.resolveSchoolFeeDue(fee, SCHOOL_ID, SESSION);

        assertThat(result).isEmpty(); // genuinely unknown — never fabricate a ₹0
    }

    @Test
    void resolveSchoolFeeDue_liveFallbackIncludesBusFeeWhenStudentTakesBus() {
        StudentFees fee = studentFeesRow(null, null, null, null);
        fee.setTakesBus(true);
        fee.setDistance(12.0);
        FeeHead tuition = feeHead(1L, "TUITION", FeeFrequency.MONTHLY, ALL_12);
        stubRules(feeRule(tuition, 200_000L));
        when(busFeesRepository.findFeesByDistanceAndAcademicYearAndSchoolId(12.0, SESSION, SCHOOL_ID))
                .thenReturn(BigDecimal.valueOf(800));

        var result = service.resolveSchoolFeeDue(fee, SCHOOL_ID, SESSION);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualByComparingTo("2800.00"); // 2000 tuition + 800 bus
    }

    // ─── computeMonthSnapshot — authoritative per-fee-head/bus line items ────────────────
    //
    // Built from the exact same applyStudentFeeConfig(...) call, inside the exact same loop,
    // that already produces baseAmountDue/discountAmount — never a second calculation. Every
    // test in this section also re-verifies the reconciliation invariant this design exists
    // to guarantee: SUM(lineItems.netPaise) == baseAmountDue(paise) + busFeeDue(paise),
    // exactly (integer paise arithmetic throughout — no rounding tolerance needed anywhere).

    private long paise(BigDecimal rupees) {
        return rupees.movePointRight(2).longValueExact();
    }

    private void assertReconciles(FeeCalculationService.MonthSnapshot s) {
        long sumOfLineItemsPaise = s.lineItems().stream().mapToLong(FeeCalculationService.LineItemSnapshot::netPaise).sum();
        long expectedPaise = paise(s.baseAmountDue()) + paise(s.busFeeDue());
        assertThat(sumOfLineItemsPaise)
                .as("sum of line-item net amounts must equal baseAmountDue + busFeeDue exactly")
                .isEqualTo(expectedPaise);
    }

    @Test
    void lineItems_singleFeeHead_oneLineItemCapturingFullIdentitySnapshot() {
        FeeHead tuition = feeHead(1L, "TUITION", FeeFrequency.MONTHLY, ALL_12);
        tuition.setName("Tuition Fee");
        stubRules(feeRule(tuition, 200_000L)); // Rs 2000
        stubConfigs();

        var s = snapshot(5, false, Set.of());

        assertThat(s.lineItems()).hasSize(1);
        var li = s.lineItems().get(0);
        assertThat(li.lineItemType()).isEqualTo("FEE_HEAD");
        assertThat(li.feeHeadId()).isEqualTo(1L);
        assertThat(li.feeHeadCode()).isEqualTo("TUITION");
        assertThat(li.feeHeadName()).isEqualTo("Tuition Fee");
        assertThat(li.frequency()).isEqualTo("MONTHLY");
        assertThat(li.grossPaise()).isEqualTo(200_000L);
        assertThat(li.discountPaise()).isEqualTo(0L);
        assertThat(li.netPaise()).isEqualTo(200_000L);
        assertThat(li.discountConfigType()).isNull();
        assertReconciles(s);
    }

    @Test
    void lineItems_multipleDynamicFeeHeads_oneLineItemEachNeverAFixedBucket() {
        // Matches the worked example from the phase brief: Tuition, Laboratory, Activity —
        // three arbitrary, school-configured fee heads, not fixed tuitionFee/labFee columns.
        FeeHead tuition = feeHead(1L, "TUITION", FeeFrequency.MONTHLY, ALL_12);
        tuition.setName("Tuition Fee");
        FeeHead lab = feeHead(2L, "LAB", FeeFrequency.MONTHLY, ALL_12);
        lab.setName("Laboratory Fee");
        FeeHead activity = feeHead(3L, "ACTIVITY", FeeFrequency.MONTHLY, ALL_12);
        activity.setName("Activity Fee");
        stubRules(feeRule(tuition, 200_000L), feeRule(lab, 60_000L), feeRule(activity, 50_000L));
        stubConfigs();

        var s = snapshot(5, false, Set.of());

        assertThat(s.lineItems()).extracting(FeeCalculationService.LineItemSnapshot::feeHeadName)
                .containsExactly("Tuition Fee", "Laboratory Fee", "Activity Fee");
        assertThat(s.baseAmountDue()).isEqualByComparingTo("3100.00"); // 2000 + 600 + 500
        assertReconciles(s);
    }

    @Test
    void lineItems_busFee_presentAsALineItemWhenStudentTakesBus() {
        FeeHead tuition = feeHead(1L, "TUITION", FeeFrequency.MONTHLY, ALL_12);
        tuition.setName("Tuition Fee");
        stubRules(feeRule(tuition, 200_000L));
        stubConfigs();
        when(busFeesRepository.findFeesByDistanceAndAcademicYearAndSchoolId(9.0, SESSION, SCHOOL_ID))
                .thenReturn(BigDecimal.valueOf(800));

        var s = snapshotWithBus(5, false, true, 9.0, Set.of());

        var busLine = s.lineItems().stream().filter(li -> li.lineItemType().equals("BUS")).findFirst();
        assertThat(busLine).isPresent();
        assertThat(busLine.get().feeHeadId()).isNull();
        assertThat(busLine.get().feeHeadCode()).isNull();
        assertThat(busLine.get().feeHeadName()).isEqualTo("Bus Fee");
        assertThat(busLine.get().grossPaise()).isEqualTo(80_000L);
        assertThat(busLine.get().netPaise()).isEqualTo(80_000L); // bus is never discounted today
        assertThat(s.lineItems()).hasSize(2); // tuition + bus, avoids double counting either
        assertReconciles(s);
    }

    @Test
    void lineItems_busFee_absentWhenStudentDoesNotTakeBus() {
        FeeHead tuition = feeHead(1L, "TUITION", FeeFrequency.MONTHLY, ALL_12);
        stubRules(feeRule(tuition, 200_000L));
        stubConfigs();

        var s = snapshotWithBus(5, false, false, null, Set.of());

        assertThat(s.lineItems()).noneMatch(li -> li.lineItemType().equals("BUS"));
        assertReconciles(s);
    }

    @Test
    void lineItems_discountPercent_preservesOriginalGrossAndDiscountReasonAlongsideNet() {
        FeeHead tuition = feeHead(1L, "TUITION", FeeFrequency.MONTHLY, ALL_12);
        tuition.setName("Tuition Fee");
        stubRules(feeRule(tuition, 200_000L));
        stubConfigs(studentFeeConfig(tuition, FeeConfigType.DISCOUNT_PERCENT, BigDecimal.valueOf(20)));

        var s = snapshot(3, false, Set.of());

        var li = s.lineItems().get(0);
        // Original charge -> discount/waiver -> final charge, all reconstructable from one row.
        assertThat(li.grossPaise()).isEqualTo(200_000L);
        assertThat(li.discountPaise()).isEqualTo(40_000L);
        assertThat(li.netPaise()).isEqualTo(160_000L);
        assertThat(li.discountConfigType()).isEqualTo("DISCOUNT_PERCENT");
        assertReconciles(s);
    }

    @Test
    void lineItems_waiver_zeroesNetButKeepsOriginalGrossVisibleForAudit() {
        FeeHead tuition = feeHead(1L, "TUITION", FeeFrequency.MONTHLY, ALL_12);
        stubRules(feeRule(tuition, 200_000L));
        stubConfigs(studentFeeConfig(tuition, FeeConfigType.WAIVER, null));

        var s = snapshot(3, false, Set.of());

        var li = s.lineItems().get(0);
        assertThat(li.grossPaise()).isEqualTo(200_000L); // the waiver never erases what was originally charged
        assertThat(li.discountPaise()).isEqualTo(200_000L);
        assertThat(li.netPaise()).isEqualTo(0L);
        assertThat(li.discountConfigType()).isEqualTo("WAIVER");
        assertReconciles(s);
    }

    @Test
    void lineItems_customAmount_grossReflectsTheOverrideNotTheOriginalRulePrice() {
        FeeHead tuition = feeHead(1L, "TUITION", FeeFrequency.MONTHLY, ALL_12);
        stubRules(feeRule(tuition, 200_000L));
        stubConfigs(studentFeeConfig(tuition, FeeConfigType.CUSTOM_AMOUNT, BigDecimal.valueOf(150_000)));

        var s = snapshot(3, false, Set.of());

        var li = s.lineItems().get(0);
        assertThat(li.grossPaise()).isEqualTo(150_000L);
        assertThat(li.discountPaise()).isEqualTo(0L);
        assertThat(li.netPaise()).isEqualTo(150_000L);
        assertReconciles(s);
    }

    @Test
    void lineItems_optOut_producesNoLineItemAtAllForTheOptedOutHead() {
        FeeHead eca = feeHead(7L, "ECA", FeeFrequency.MONTHLY, ALL_12);
        eca.setOptional(true);
        stubRules(feeRule(eca, 50_000L));
        stubConfigs(studentFeeConfig(eca, FeeConfigType.OPT_OUT, null));

        var s = snapshot(3, false, Set.of());

        assertThat(s.lineItems()).isEmpty(); // excluded entirely, not a ₹0 line item
        assertReconciles(s);
    }

    @Test
    void lineItems_oneTimeFee_presentOnFirstCharge_absentWhenAlreadyCharged() {
        FeeHead admission = feeHead(5L, "ADMISSION", FeeFrequency.ONE_TIME, ALL_12);
        admission.setName("Admission Fee");
        stubRules(feeRule(admission, 1_000_000L));
        stubConfigs();

        var firstCharge = snapshot(6, true, Set.of());
        assertThat(firstCharge.lineItems()).hasSize(1);
        assertThat(firstCharge.lineItems().get(0).feeHeadName()).isEqualTo("Admission Fee");
        assertReconciles(firstCharge);

        var alreadyCharged = snapshot(1, false, Set.of(5L));
        assertThat(alreadyCharged.lineItems()).isEmpty();
        assertReconciles(alreadyCharged);
    }

    @Test
    void lineItems_quarterlyMidSessionJoiner_catchUpChargeStillProducesALineItem() {
        FeeHead lab = feeHead(2L, "LAB", FeeFrequency.QUARTERLY, ALL_12);
        lab.setName("Laboratory Fee");
        stubRules(feeRule(lab, 60_000L));
        stubConfigs();

        var s = snapshot(5, true, Set.of()); // mid-quarter join, catch-up charge

        assertThat(s.lineItems()).hasSize(1);
        assertThat(s.lineItems().get(0).frequency()).isEqualTo("QUARTERLY");
        assertThat(s.lineItems().get(0).netPaise()).isEqualTo(60_000L);
        assertReconciles(s);
    }

    @Test
    void lineItems_richScenario_multipleHeadsPlusDiscountPlusBus_reconcilesExactly() {
        // The full worked example from the phase brief: Tuition, Laboratory, Activity, a
        // scholarship discount on tuition, and a bus fee — all in one month.
        FeeHead tuition = feeHead(1L, "TUITION", FeeFrequency.MONTHLY, ALL_12);
        tuition.setName("Tuition Fee");
        FeeHead lab = feeHead(2L, "LAB", FeeFrequency.MONTHLY, ALL_12);
        lab.setName("Laboratory Fee");
        FeeHead activity = feeHead(3L, "ACTIVITY", FeeFrequency.MONTHLY, ALL_12);
        activity.setName("Activity Fee");
        stubRules(feeRule(tuition, 200_000L), feeRule(lab, 60_000L), feeRule(activity, 50_000L));
        stubConfigs(studentFeeConfig(tuition, FeeConfigType.DISCOUNT_FIXED, BigDecimal.valueOf(40_000)));
        when(busFeesRepository.findFeesByDistanceAndAcademicYearAndSchoolId(9.0, SESSION, SCHOOL_ID))
                .thenReturn(BigDecimal.valueOf(800));

        var s = snapshotWithBus(8, false, true, 9.0, Set.of());

        // (2000 + 600 + 500) - 400(discount) = 2700 school fee, + 800 bus = 3500 total.
        assertThat(s.baseAmountDue()).isEqualByComparingTo("2700.00");
        assertThat(s.busFeeDue()).isEqualByComparingTo("800.00");
        assertThat(s.lineItems()).hasSize(4); // 3 fee heads + bus
        assertReconciles(s);

        // resolveSchoolFeeDue (the payment/checkout-facing figure) must agree with the same
        // total the line items sum to — never "one calc for total, another for line items."
        StudentFees fee = studentFeesRow(s.baseAmountDue(), s.busFeeDue(), s.discountAmount(), SnapshotStatus.COMPUTED);
        var due = service.resolveSchoolFeeDue(fee, SCHOOL_ID, SESSION);
        assertThat(due).isPresent();
        assertThat(paise(due.get())).isEqualTo(s.lineItems().stream()
                .mapToLong(FeeCalculationService.LineItemSnapshot::netPaise).sum());
    }
}
