package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.RecalculationEntryDto;
import com.indraacademy.ias_management.entity.LineItemType;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.SnapshotStatus;
import com.indraacademy.ias_management.entity.StudentFees;
import com.indraacademy.ias_management.entity.StudentFeesLineItem;
import com.indraacademy.ias_management.repository.AllocationRefundRepository;
import com.indraacademy.ias_management.repository.PaymentStudentFeesAllocationRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentFeesLineItemRepository;
import com.indraacademy.ias_management.repository.StudentFeesRepository;
import com.indraacademy.ias_management.repository.StudentOneTimeFeeChargedRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 5A: StudentFeesRecalculationService.preview/recalculateOne. FeeCalculationService is
 * mocked here (matching StudentFeesServiceTest/StudentFeesGenerationServiceTest's existing
 * convention) — per-frequency/dueMonths/asOf correctness is FeeCalculationServiceTest's job;
 * this class owns eligibility gating, isFirstRow/asOfDate threading, atomic snapshot +
 * line-item replacement, and audit content.
 */
@ExtendWith(MockitoExtension.class)
class StudentFeesRecalculationServiceTest {

    @Mock private StudentFeesRepository studentFeesRepository;
    @Mock private SchoolRepository schoolRepository;
    @Mock private FeeCalculationService feeCalculationService;
    @Mock private StudentFeesLineItemRepository studentFeesLineItemRepository;
    @Mock private StudentOneTimeFeeChargedRepository studentOneTimeFeeChargedRepository;
    @Mock private PaymentStudentFeesAllocationRepository paymentAllocationRepository;
    @Mock private AllocationRefundRepository allocationRefundRepository;
    @Mock private AuditService auditService;
    @Mock private SecurityUtil securityUtil;

    private StudentFeesRecalculationService service;

    private static final Long SCHOOL_ID = 1L;
    private static final String STUDENT_ID = "S1";
    private static final String SESSION = "2025-2026";

    @BeforeEach
    void setUp() {
        service = new StudentFeesRecalculationService();
        ReflectionTestUtils.setField(service, "studentFeesRepository", studentFeesRepository);
        ReflectionTestUtils.setField(service, "schoolRepository", schoolRepository);
        ReflectionTestUtils.setField(service, "feeCalculationService", feeCalculationService);
        ReflectionTestUtils.setField(service, "studentFeesLineItemRepository", studentFeesLineItemRepository);
        ReflectionTestUtils.setField(service, "studentOneTimeFeeChargedRepository", studentOneTimeFeeChargedRepository);
        ReflectionTestUtils.setField(service, "paymentAllocationRepository", paymentAllocationRepository);
        ReflectionTestUtils.setField(service, "allocationRefundRepository", allocationRefundRepository);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        lenient().when(securityUtil.getUsername()).thenReturn("admin1");
        lenient().when(securityUtil.getRole()).thenReturn("ADMIN");

        lenient().when(paymentAllocationRepository.sumAmountPaiseByStudentFeesId(any())).thenReturn(0L);
        lenient().when(allocationRefundRepository.sumAmountPaiseByStudentFeesId(any())).thenReturn(0L);
        lenient().when(studentOneTimeFeeChargedRepository.findFeeHeadIdBySchoolIdAndStudentId(any(), any())).thenReturn(Set.of());
        lenient().when(studentFeesLineItemRepository.findByStudentFeesIdAndSupersededAtIsNullOrderById(any())).thenReturn(List.of());
        lenient().when(feeCalculationService.validateFeeConfiguration(any(), any(), any()))
                .thenReturn(FeeCalculationService.FeeConfigurationStatus.ok());
        lenient().when(feeCalculationService.parseSession(anyString())).thenAnswer(inv -> {
            String s = inv.getArgument(0);
            String[] parts = s.split("-");
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        });
        lenient().when(feeCalculationService.academicMonthStart(anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(LocalDate.of(2025, 6, 1));

        School school = new School();
        school.setId(SCHOOL_ID);
        school.setAcademicYearStartMonth(4);
        lenient().when(schoolRepository.findById(SCHOOL_ID)).thenReturn(java.util.Optional.of(school));
    }

    /** A clean, never-touched-by-money unpaid row — stubs both the locking (Apply) and plain
     * (Preview) finders so either code path finds the same row instance. */
    private StudentFees unpaidRow(int month, BigDecimal base, BigDecimal bus, BigDecimal discount) {
        StudentFees fee = new StudentFees();
        fee.setId(500L + month);
        fee.setStudentId(STUDENT_ID);
        fee.setSchoolId(SCHOOL_ID);
        fee.setYear(SESSION);
        fee.setMonth(month);
        fee.setClassName("6A");
        fee.setPaid(false);
        fee.setManuallyPaid(false);
        fee.setAmountPaid(null);
        fee.setBaseAmountDue(base);
        fee.setBusFeeDue(bus);
        fee.setDiscountAmount(discount);
        fee.setSnapshotStatus(SnapshotStatus.COMPUTED);
        fee.setAmountRuleSnapshot("{\"old\":true}");
        lenient().when(studentFeesRepository.findByStudentIdAndSchoolIdAndYearAndMonth(STUDENT_ID, SCHOOL_ID, SESSION, month))
                .thenReturn(fee);
        lenient().when(studentFeesRepository.findByStudentIdAndSchoolIdAndYearAndMonthForUpdate(STUDENT_ID, SCHOOL_ID, SESSION, month))
                .thenReturn(fee);
        lenient().when(studentFeesRepository.findByStudentIdAndSchoolIdAndYearOrderByMonthAsc(STUDENT_ID, SCHOOL_ID, SESSION))
                .thenReturn(List.of(fee));
        return fee;
    }

    private FeeCalculationService.LineItemSnapshot feeHeadLine(String code, String name, long grossPaise, long discountPaise, String discountConfigType) {
        return new FeeCalculationService.LineItemSnapshot(LineItemType.FEE_HEAD.name(), 77L, code, name, "MONTHLY", grossPaise, discountPaise, discountConfigType);
    }

    private FeeCalculationService.LineItemSnapshot busLine(long grossPaise) {
        return new FeeCalculationService.LineItemSnapshot(LineItemType.BUS.name(), null, null, "Bus Fee", null, grossPaise, 0L, null);
    }

    private void stubSnapshot(FeeCalculationService.MonthSnapshot snapshot) {
        when(feeCalculationService.computeMonthSnapshot(
                eq(SCHOOL_ID), eq(SESSION), anyString(), eq(STUDENT_ID), anyInt(), anyBoolean(), any(), any(), any(), any()))
                .thenReturn(snapshot);
    }

    // ─── Happy path: clean unpaid row, fee-rule/bus/discount all change ────────────────

    @Test
    void recalculateOne_cleanUnpaidRow_persistsNewSnapshotAndLineItems_reconcilesExactly() {
        StudentFees fee = unpaidRow(3, BigDecimal.valueOf(2000), BigDecimal.valueOf(500), BigDecimal.ZERO);
        FeeCalculationService.MonthSnapshot newSnapshot = new FeeCalculationService.MonthSnapshot(
                BigDecimal.valueOf(270000, 2), BigDecimal.valueOf(60000, 2), BigDecimal.valueOf(30000, 2),
                "{\"new\":true}", List.of(), SnapshotStatus.COMPUTED,
                List.of(feeHeadLine("TUITION", "Tuition Fee", 300000L, 30000L, "DISCOUNT_FIXED"), busLine(60000L)));
        stubSnapshot(newSnapshot);

        RecalculationEntryDto result = service.recalculateOne(STUDENT_ID, SESSION, 3, "Fixed a mis-keyed tuition rule", "127.0.0.1");

        assertThat(result.isOk()).isTrue();
        assertThat(result.getOldBaseAmountDue()).isEqualByComparingTo("2000");
        assertThat(result.getNewBaseAmountDue()).isEqualByComparingTo("2700");
        assertThat(result.getNewBusFeeDue()).isEqualByComparingTo("600");
        assertThat(result.getNewDiscountAmount()).isEqualByComparingTo("300");
        assertThat(result.getNewTotalDue()).isEqualByComparingTo("3300"); // base(already net) + bus, never minus discount again

        assertThat(fee.getBaseAmountDue()).isEqualByComparingTo("2700");
        assertThat(fee.getBusFeeDue()).isEqualByComparingTo("600");
        assertThat(fee.getAmountRuleSnapshot()).isEqualTo("{\"new\":true}");
        verify(studentFeesRepository).save(fee);

        ArgumentCaptor<StudentFeesLineItem> captor = ArgumentCaptor.forClass(StudentFeesLineItem.class);
        verify(studentFeesLineItemRepository, times(2)).save(captor.capture());
        List<StudentFeesLineItem> saved = captor.getAllValues();
        assertThat(saved).allSatisfy(li -> assertThat(li.getSupersededAt()).isNull());
        assertThat(saved).extracting(StudentFeesLineItem::getNetAmountPaise).containsExactlyInAnyOrder(270000L, 60000L);

        long lineItemTotalPaise = saved.stream().mapToLong(StudentFeesLineItem::getNetAmountPaise).sum();
        long expectedTotalPaise = fee.getBaseAmountDue().movePointRight(2).longValueExact()
                + fee.getBusFeeDue().movePointRight(2).longValueExact();
        assertThat(lineItemTotalPaise).isEqualTo(expectedTotalPaise); // SUM(active lineItem.net) == schoolFeeDue
    }

    @Test
    void recalculateOne_priorActiveLineItems_areSupersededNeverDeleted() {
        StudentFees fee = unpaidRow(4, BigDecimal.valueOf(1000), BigDecimal.ZERO, BigDecimal.ZERO);
        StudentFeesLineItem oldLine = new StudentFeesLineItem();
        oldLine.setId(9001L);
        oldLine.setStudentFeesId(fee.getId());
        oldLine.setLineItemType(LineItemType.FEE_HEAD);
        oldLine.setFeeHeadId(77L);
        oldLine.setNetAmountPaise(100000L);
        when(studentFeesLineItemRepository.findByStudentFeesIdAndSupersededAtIsNullOrderById(fee.getId()))
                .thenReturn(List.of(oldLine));
        stubSnapshot(new FeeCalculationService.MonthSnapshot(
                BigDecimal.valueOf(120000, 2), BigDecimal.ZERO, BigDecimal.ZERO, "{}", List.of(), SnapshotStatus.COMPUTED,
                List.of(feeHeadLine("TUITION", "Tuition Fee", 120000L, 0L, null))));

        service.recalculateOne(STUDENT_ID, SESSION, 4, "rule correction", "127.0.0.1");

        // The old row is superseded (never deleted) — same object, save() called with
        // supersededAt now set.
        assertThat(oldLine.getSupersededAt()).isNotNull();
        verify(studentFeesLineItemRepository).save(oldLine);
        verify(studentFeesLineItemRepository, never()).delete(any());
        verify(studentFeesLineItemRepository, never()).deleteById(any());
    }

    /** Regression test for a real Postgres constraint violation found in end-to-end testing:
     * Hibernate's automatic flush orders ALL pending inserts before ALL pending updates
     * regardless of save() call order, so without an explicit flush() between superseding
     * the old row and inserting the new one, a recalculation touching a fee head that
     * already has an active line item collides with uq_sfli_studentfees_feehead_active
     * before the supersede UPDATE has physically landed. A Mockito-mocked repository can't
     * reproduce that ordering bug directly, but it CAN verify the fix's mechanism —
     * flush() is called, and it's called after every supersede save() and before any new
     * line-item save(). */
    @Test
    void recalculateOne_flushesSupersededLineItemsBeforeInsertingNewOnes() {
        StudentFees fee = unpaidRow(4, BigDecimal.valueOf(1000), BigDecimal.ZERO, BigDecimal.ZERO);
        StudentFeesLineItem oldLine = new StudentFeesLineItem();
        oldLine.setId(9002L);
        oldLine.setStudentFeesId(fee.getId());
        oldLine.setLineItemType(LineItemType.FEE_HEAD);
        oldLine.setFeeHeadId(77L);
        oldLine.setNetAmountPaise(100000L);
        when(studentFeesLineItemRepository.findByStudentFeesIdAndSupersededAtIsNullOrderById(fee.getId()))
                .thenReturn(List.of(oldLine));
        stubSnapshot(new FeeCalculationService.MonthSnapshot(
                BigDecimal.valueOf(120000, 2), BigDecimal.ZERO, BigDecimal.ZERO, "{}", List.of(), SnapshotStatus.COMPUTED,
                List.of(feeHeadLine("TUITION", "Tuition Fee", 120000L, 0L, null))));

        service.recalculateOne(STUDENT_ID, SESSION, 4, "rule correction", "127.0.0.1");

        InOrder order = inOrder(studentFeesLineItemRepository);
        order.verify(studentFeesLineItemRepository).save(oldLine);
        order.verify(studentFeesLineItemRepository).flush();
        order.verify(studentFeesLineItemRepository).save(argThat(li -> li != oldLine));
    }

    // ─── Arbitrary custom fee head — no Tuition/Lab/ECA/Examination assumptions ────────

    @Test
    void recalculateOne_arbitraryCustomFeeHead_persistedVerbatim() {
        unpaidRow(5, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        stubSnapshot(new FeeCalculationService.MonthSnapshot(
                BigDecimal.valueOf(45000, 2), BigDecimal.ZERO, BigDecimal.ZERO, "{}", List.of(), SnapshotStatus.COMPUTED,
                List.of(new FeeCalculationService.LineItemSnapshot(
                        LineItemType.FEE_HEAD.name(), 999L, "ROBOTICS_CLUB_2026", "Robotics Club Enrolment 2026",
                        "QUARTERLY", 45000L, 0L, null))));

        service.recalculateOne(STUDENT_ID, SESSION, 5, "new arbitrary fee head configured", "127.0.0.1");

        ArgumentCaptor<StudentFeesLineItem> captor = ArgumentCaptor.forClass(StudentFeesLineItem.class);
        verify(studentFeesLineItemRepository).save(captor.capture());
        assertThat(captor.getValue().getFeeHeadName()).isEqualTo("Robotics Club Enrolment 2026");
        assertThat(captor.getValue().getFeeHeadCode()).isEqualTo("ROBOTICS_CLUB_2026");
    }

    // ─── isFirstRow / asOfDate threading (quarterly/annual/custom dueMonths + non-April) ─

    @Test
    void recalculateOne_earliestRowForSession_isTreatedAsFirstRow() {
        StudentFees fee = unpaidRow(6, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO); // only row -> earliest
        stubSnapshot(new FeeCalculationService.MonthSnapshot(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "{}", List.of(), SnapshotStatus.COMPUTED, List.of()));

        service.recalculateOne(STUDENT_ID, SESSION, 6, "join-month catch-up billing check", "127.0.0.1");

        verify(feeCalculationService).computeMonthSnapshot(
                eq(SCHOOL_ID), eq(SESSION), eq("6A"), eq(STUDENT_ID), eq(6), eq(true), any(), any(), any(), any());
    }

    @Test
    void recalculateOne_laterRowWithEarlierSiblingPresent_isNotFirstRow() {
        StudentFees month1 = unpaidRow(1, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        StudentFees month3 = unpaidRow(3, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        when(studentFeesRepository.findByStudentIdAndSchoolIdAndYearOrderByMonthAsc(STUDENT_ID, SCHOOL_ID, SESSION))
                .thenReturn(List.of(month1, month3));
        stubSnapshot(new FeeCalculationService.MonthSnapshot(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "{}", List.of(), SnapshotStatus.COMPUTED, List.of()));

        service.recalculateOne(STUDENT_ID, SESSION, 3, "quarterly rule correction", "127.0.0.1");

        verify(feeCalculationService).computeMonthSnapshot(
                eq(SCHOOL_ID), eq(SESSION), anyString(), eq(STUDENT_ID), eq(3), eq(false), any(), any(), any(), any());
    }

    @Test
    void recalculateOne_nonAprilStartSchool_asOfDateUsesSchoolsOwnStartMonth() {
        School school = new School();
        school.setId(SCHOOL_ID);
        school.setAcademicYearStartMonth(1); // January-start school
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(java.util.Optional.of(school));
        when(feeCalculationService.parseSession(SESSION)).thenReturn(new int[]{2025, 2026});
        LocalDate expectedAsOf = LocalDate.of(2025, 3, 1); // academic month 3 of a Jan-start school
        when(feeCalculationService.academicMonthStart(3, 2025, 2026, 1)).thenReturn(expectedAsOf);
        unpaidRow(3, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        stubSnapshot(new FeeCalculationService.MonthSnapshot(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "{}", List.of(), SnapshotStatus.COMPUTED, List.of()));

        service.recalculateOne(STUDENT_ID, SESSION, 3, "verify non-April academic month math", "127.0.0.1");

        verify(feeCalculationService).computeMonthSnapshot(
                eq(SCHOOL_ID), eq(SESSION), anyString(), eq(STUDENT_ID), eq(3), anyBoolean(), eq(expectedAsOf), any(), any(), any());
    }

    // ─── ONE_TIME dedup replay: this row's own current charge isn't treated as "elsewhere" ─

    @Test
    void recalculateOne_oneTimeFeeHeadAlreadyOnThisRow_excludedFromDedupSetPassedIn() {
        StudentFees fee = unpaidRow(1, BigDecimal.valueOf(500), BigDecimal.ZERO, BigDecimal.ZERO);
        StudentFeesLineItem existingOneTime = new StudentFeesLineItem();
        existingOneTime.setId(9100L);
        existingOneTime.setStudentFeesId(fee.getId());
        existingOneTime.setLineItemType(LineItemType.FEE_HEAD);
        existingOneTime.setFeeHeadId(55L);
        existingOneTime.setNetAmountPaise(50000L);
        when(studentFeesLineItemRepository.findByStudentFeesIdAndSupersededAtIsNullOrderById(fee.getId()))
                .thenReturn(List.of(existingOneTime));
        when(studentOneTimeFeeChargedRepository.findFeeHeadIdBySchoolIdAndStudentId(SCHOOL_ID, STUDENT_ID))
                .thenReturn(Set.of(55L)); // recorded as charged — by this very row, originally
        stubSnapshot(new FeeCalculationService.MonthSnapshot(
                BigDecimal.valueOf(50000, 2), BigDecimal.ZERO, BigDecimal.ZERO, "{}", List.of(), SnapshotStatus.COMPUTED,
                List.of(feeHeadLine("REG", "Registration Fee", 50000L, 0L, null))));

        service.recalculateOne(STUDENT_ID, SESSION, 1, "one-time fee head sanity check", "127.0.0.1");

        ArgumentCaptor<Set<Long>> setCaptor = ArgumentCaptor.forClass(Set.class);
        verify(feeCalculationService).computeMonthSnapshot(
                any(), any(), any(), any(), anyInt(), anyBoolean(), any(), any(), any(), setCaptor.capture());
        assertThat(setCaptor.getValue()).doesNotContain(55L); // excluded, not double-blocked
    }

    // ─── Eligibility gating — the critical safety rule ─────────────────────────────────

    @Test
    void recalculateOne_rowWithPaymentAllocationHistory_rejectedNotGuessed() {
        StudentFees fee = unpaidRow(1, BigDecimal.valueOf(2000), BigDecimal.ZERO, BigDecimal.ZERO);
        when(paymentAllocationRepository.sumAmountPaiseByStudentFeesId(fee.getId())).thenReturn(200000L);

        RecalculationEntryDto result = service.recalculateOne(STUDENT_ID, SESSION, 1, "attempt", "127.0.0.1");

        assertThat(result.isOk()).isFalse();
        assertThat(result.getMessage()).containsIgnoringCase("allocation");
        verify(studentFeesRepository, never()).save(any());
        verify(studentFeesLineItemRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void recalculateOne_partiallyPaidMonth_rejected() {
        StudentFees fee = unpaidRow(1, BigDecimal.valueOf(2000), BigDecimal.ZERO, BigDecimal.ZERO);
        fee.setAmountPaid(BigDecimal.valueOf(500)); // partial payment recorded

        RecalculationEntryDto result = service.recalculateOne(STUDENT_ID, SESSION, 1, "attempt", "127.0.0.1");

        assertThat(result.isOk()).isFalse();
        assertThat(result.getMessage()).containsIgnoringCase("amountPaid");
        verify(studentFeesRepository, never()).save(any());
    }

    @Test
    void recalculateOne_fullyPaidMonth_rejected() {
        StudentFees fee = unpaidRow(1, BigDecimal.valueOf(2000), BigDecimal.ZERO, BigDecimal.ZERO);
        fee.setPaid(true);

        RecalculationEntryDto result = service.recalculateOne(STUDENT_ID, SESSION, 1, "attempt", "127.0.0.1");

        assertThat(result.isOk()).isFalse();
        assertThat(result.getMessage()).containsIgnoringCase("paid");
        verify(studentFeesRepository, never()).save(any());
    }

    @Test
    void recalculateOne_manuallyPaidMonth_rejected() {
        StudentFees fee = unpaidRow(1, BigDecimal.valueOf(2000), BigDecimal.ZERO, BigDecimal.ZERO);
        fee.setManuallyPaid(true);

        RecalculationEntryDto result = service.recalculateOne(STUDENT_ID, SESSION, 1, "attempt", "127.0.0.1");

        assertThat(result.isOk()).isFalse();
        verify(studentFeesRepository, never()).save(any());
    }

    @Test
    void recalculateOne_refundedBackToNetZero_stillRejected_neverGuessedSafe() {
        // Reversal/refund history recorded against this row even though gross allocation
        // itself currently sums to zero — the ledger still shows real financial activity
        // against this exact bill. The explicit instruction is to reject rather than guess.
        StudentFees fee = unpaidRow(1, BigDecimal.valueOf(2000), BigDecimal.ZERO, BigDecimal.ZERO);
        when(allocationRefundRepository.sumAmountPaiseByStudentFeesId(fee.getId())).thenReturn(200000L);

        RecalculationEntryDto result = service.recalculateOne(STUDENT_ID, SESSION, 1, "attempt", "127.0.0.1");

        assertThat(result.isOk()).isFalse();
        assertThat(result.getMessage()).containsIgnoringCase("refund");
        verify(studentFeesRepository, never()).save(any());
    }

    @Test
    void recalculateOne_unknownPaidStatus_rejectedRatherThanAssumedSafe() {
        StudentFees fee = unpaidRow(1, BigDecimal.valueOf(2000), BigDecimal.ZERO, BigDecimal.ZERO);
        fee.setPaid(null); // unexpected state — must not be treated as "definitely unpaid"

        RecalculationEntryDto result = service.recalculateOne(STUDENT_ID, SESSION, 1, "attempt", "127.0.0.1");

        assertThat(result.isOk()).isFalse();
        verify(studentFeesRepository, never()).save(any());
    }

    @Test
    void recalculateOne_noValidFeeConfiguration_rejected() {
        unpaidRow(1, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        when(feeCalculationService.validateFeeConfiguration(SCHOOL_ID, SESSION, "6A"))
                .thenReturn(FeeCalculationService.FeeConfigurationStatus.fail("no rules configured"));

        RecalculationEntryDto result = service.recalculateOne(STUDENT_ID, SESSION, 1, "attempt", "127.0.0.1");

        assertThat(result.isOk()).isFalse();
        assertThat(result.getMessage()).contains("no rules configured");
        verify(studentFeesRepository, never()).save(any());
    }

    // ─── Cross-school / not-found ───────────────────────────────────────────────────────

    @Test
    void recalculateOne_crossSchoolRequest_rejectedAsNotFound() {
        // The row exists, but under a DIFFERENT school than the caller's own — the
        // schoolId-scoped finder simply returns null, exactly like every other endpoint.
        when(studentFeesRepository.findByStudentIdAndSchoolIdAndYearAndMonthForUpdate(STUDENT_ID, SCHOOL_ID, SESSION, 1))
                .thenReturn(null);

        RecalculationEntryDto result = service.recalculateOne(STUDENT_ID, SESSION, 1, "attempt", "127.0.0.1");

        assertThat(result.isOk()).isFalse();
        assertThat(result.getMessage()).containsIgnoringCase("no studentfees row");
        verify(studentFeesRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void recalculateOne_blankReason_rejectedBeforeAnyLookup() {
        RecalculationEntryDto result = service.recalculateOne(STUDENT_ID, SESSION, 1, "   ", "127.0.0.1");

        assertThat(result.isOk()).isFalse();
        verifyNoInteractions(studentFeesRepository);
    }

    // ─── Preview: read-only, never writes ───────────────────────────────────────────────

    @Test
    void preview_performsNoWrites_evenForEligibleMonths() {
        unpaidRow(1, BigDecimal.valueOf(2000), BigDecimal.ZERO, BigDecimal.ZERO);
        stubSnapshot(new FeeCalculationService.MonthSnapshot(
                BigDecimal.valueOf(250000, 2), BigDecimal.ZERO, BigDecimal.ZERO, "{}", List.of(), SnapshotStatus.COMPUTED,
                List.of(feeHeadLine("TUITION", "Tuition Fee", 250000L, 0L, null))));

        List<RecalculationEntryDto> results = service.preview(STUDENT_ID, SESSION, List.of(1));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).isOk()).isTrue();
        assertThat(results.get(0).getNewBaseAmountDue()).isEqualByComparingTo("2500");
        verify(studentFeesRepository, never()).save(any());
        verify(studentFeesLineItemRepository, never()).save(any());
        verify(studentOneTimeFeeChargedRepository, never()).save(any());
        verifyNoInteractions(auditService);
        // Preview must not lock the row — only the plain finder is used, never ForUpdate.
        verify(studentFeesRepository, never()).findByStudentIdAndSchoolIdAndYearAndMonthForUpdate(any(), any(), any(), anyInt());
    }

    @Test
    void preview_ineligibleMonth_reportsWhyWithoutComputingNewValues() {
        StudentFees fee = unpaidRow(1, BigDecimal.valueOf(2000), BigDecimal.ZERO, BigDecimal.ZERO);
        fee.setPaid(true);

        List<RecalculationEntryDto> results = service.preview(STUDENT_ID, SESSION, List.of(1));

        assertThat(results.get(0).isOk()).isFalse();
        assertThat(results.get(0).getMessage()).isNotBlank();
        assertThat(results.get(0).getNewBaseAmountDue()).isNull();
        verify(feeCalculationService, never()).computeMonthSnapshot(
                any(), any(), any(), any(), anyInt(), anyBoolean(), any(), any(), any(), any());
    }

    // ─── Apply recomputes rather than trusting Preview ──────────────────────────────────

    @Test
    void applyRecomputesIndependently_neverTrustsWhatPreviewReturned() {
        unpaidRow(1, BigDecimal.valueOf(2000), BigDecimal.ZERO, BigDecimal.ZERO);

        FeeCalculationService.MonthSnapshot previewTimeSnapshot = new FeeCalculationService.MonthSnapshot(
                BigDecimal.valueOf(250000, 2), BigDecimal.ZERO, BigDecimal.ZERO, "{}", List.of(), SnapshotStatus.COMPUTED,
                List.of(feeHeadLine("TUITION", "Tuition Fee", 250000L, 0L, null)));
        stubSnapshot(previewTimeSnapshot);
        List<RecalculationEntryDto> preview = service.preview(STUDENT_ID, SESSION, List.of(1));
        assertThat(preview.get(0).getNewBaseAmountDue()).isEqualByComparingTo("2500");

        // Configuration changes again between preview and apply (e.g. another admin edit).
        FeeCalculationService.MonthSnapshot applyTimeSnapshot = new FeeCalculationService.MonthSnapshot(
                BigDecimal.valueOf(300000, 2), BigDecimal.ZERO, BigDecimal.ZERO, "{}", List.of(), SnapshotStatus.COMPUTED,
                List.of(feeHeadLine("TUITION", "Tuition Fee", 300000L, 0L, null)));
        reset(feeCalculationService);
        lenient().when(feeCalculationService.validateFeeConfiguration(any(), any(), any()))
                .thenReturn(FeeCalculationService.FeeConfigurationStatus.ok());
        lenient().when(feeCalculationService.academicMonthStart(anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(LocalDate.of(2025, 6, 1));
        when(feeCalculationService.parseSession(SESSION)).thenReturn(new int[]{2025, 2026});
        when(feeCalculationService.computeMonthSnapshot(any(), any(), any(), any(), anyInt(), anyBoolean(), any(), any(), any(), any()))
                .thenReturn(applyTimeSnapshot);

        RecalculationEntryDto applied = service.recalculateOne(STUDENT_ID, SESSION, 1, "config changed again before apply", "127.0.0.1");

        assertThat(applied.getNewBaseAmountDue()).isEqualByComparingTo("3000"); // apply-time value, not preview's 2500
    }

    // ─── Audit content ───────────────────────────────────────────────────────────────────

    @Test
    void recalculateOne_auditsExactOldAndNewValuesPlusReason() {
        StudentFees fee = unpaidRow(2, BigDecimal.valueOf(2000), BigDecimal.valueOf(300), BigDecimal.valueOf(100));
        stubSnapshot(new FeeCalculationService.MonthSnapshot(
                BigDecimal.valueOf(280000, 2), BigDecimal.valueOf(40000, 2), BigDecimal.valueOf(20000, 2),
                "{\"rule\":\"v2\"}", List.of(), SnapshotStatus.COMPUTED,
                List.of(feeHeadLine("TUITION", "Tuition Fee", 300000L, 20000L, "DISCOUNT_FIXED"))));

        service.recalculateOne(STUDENT_ID, SESSION, 2, "admin corrected a mis-keyed discount", "127.0.0.1");

        ArgumentCaptor<String> oldJsonCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> newJsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(eq("admin1"), eq("ADMIN"), eq("RECALCULATE_STUDENT_FEES"), eq("StudentFees"),
                eq(fee.getId().toString()), oldJsonCaptor.capture(), newJsonCaptor.capture(), eq("127.0.0.1"));

        String oldJson = oldJsonCaptor.getValue();
        String newJson = newJsonCaptor.getValue();
        assertThat(oldJson).contains("\"baseAmountDue\":2000").contains("\"busFeeDue\":300").contains("\"discountAmount\":100");
        assertThat(newJson).contains("\"baseAmountDue\":2800").contains("\"busFeeDue\":400").contains("\"discountAmount\":200")
                .contains("\"reason\":\"admin corrected a mis-keyed discount\"")
                .contains("\"studentId\":\"S1\"")
                .contains("\"session\":\"2025-2026\"")
                .contains("\"month\":2")
                .contains("\"amountRuleSnapshot\":\"{\\\"rule\\\":\\\"v2\\\"}\"");
    }

    // ─── Failure propagates (rollback relies on @Transactional + a propagated exception) ─

    @Test
    void recalculateOne_lineItemSaveFails_exceptionPropagates_snapshotChangeNotSilentlyKept() {
        StudentFees fee = unpaidRow(1, BigDecimal.valueOf(2000), BigDecimal.ZERO, BigDecimal.ZERO);
        stubSnapshot(new FeeCalculationService.MonthSnapshot(
                BigDecimal.valueOf(250000, 2), BigDecimal.ZERO, BigDecimal.ZERO, "{}", List.of(), SnapshotStatus.COMPUTED,
                List.of(feeHeadLine("TUITION", "Tuition Fee", 250000L, 0L, null))));
        when(studentFeesLineItemRepository.save(any(StudentFeesLineItem.class)))
                .thenThrow(new RuntimeException("simulated DB failure writing line item"));

        // @Transactional relies on this propagating uncaught to trigger a real rollback in
        // production — a unit test can't exercise the DB rollback itself (no Testcontainers
        // here), but it CAN prove the method doesn't swallow the failure and report success.
        assertThatThrownBy(() -> service.recalculateOne(STUDENT_ID, SESSION, 1, "attempt", "127.0.0.1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("simulated DB failure");

        verifyNoInteractions(auditService); // no audit entry for a recalculation that never actually completed
    }
}
