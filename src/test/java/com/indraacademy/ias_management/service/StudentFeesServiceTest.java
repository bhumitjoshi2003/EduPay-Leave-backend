package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.CheckoutQuoteDto;
import com.indraacademy.ias_management.dto.ManualPaymentRequest;
import com.indraacademy.ias_management.dto.MonthFeeBreakdownDto;
import com.indraacademy.ias_management.dto.StudentFeesAdminUpdateRequest;
import com.indraacademy.ias_management.dto.StudentFeesCreateRequest;
import com.indraacademy.ias_management.entity.LineItemType;
import com.indraacademy.ias_management.entity.Payment;
import com.indraacademy.ias_management.entity.PaymentStudentFeesAllocation;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.SnapshotStatus;
import com.indraacademy.ias_management.entity.StudentFees;
import com.indraacademy.ias_management.entity.StudentFeesLineItem;
import com.indraacademy.ias_management.entity.StudentOneTimeFeeCharged;
import com.indraacademy.ias_management.repository.AllocationRefundRepository;
import com.indraacademy.ias_management.repository.PaymentRepository;
import com.indraacademy.ias_management.repository.PaymentStudentFeesAllocationRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentFeesLineItemRepository;
import com.indraacademy.ias_management.repository.StudentFeesRepository;
import com.indraacademy.ias_management.repository.StudentOneTimeFeeChargedRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Sub-phase 1b: StudentFeesService.createDefaultStudentFees is the mid-session-admission
 * generation path (a brand-new student registering partway through a session) — its rows
 * must start at the student's own joining academic month (never earlier), and only the
 * VERY FIRST row uses the mid-session-join policy (appliesAtJoin, via isFirstRow=true);
 * every later row uses the normal school-wide schedule, exactly like a continuing student's
 * rows in StudentFeesGenerationServiceTest.
 */
@ExtendWith(MockitoExtension.class)
class StudentFeesServiceTest {

    @Mock private StudentFeesRepository studentFeesRepository;
    @Mock private SchoolRepository schoolRepository;
    @Mock private FeeCalculationService feeCalculationService;
    @Mock private AuditService auditService;
    @Mock private SecurityUtil securityUtil;
    @Mock private StudentOneTimeFeeChargedRepository studentOneTimeFeeChargedRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private PaymentStudentFeesAllocationRepository paymentAllocationRepository;
    @Mock private AllocationRefundRepository allocationRefundRepository;
    @Mock private StudentFeesLineItemRepository studentFeesLineItemRepository;

    private StudentFeesService service;

    private static final Long SCHOOL_ID = 1L;

    @BeforeEach
    void setUp() {
        service = new StudentFeesService();
        ReflectionTestUtils.setField(service, "studentFeesRepository", studentFeesRepository);
        ReflectionTestUtils.setField(service, "schoolRepository", schoolRepository);
        ReflectionTestUtils.setField(service, "feeCalculationService", feeCalculationService);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(service, "studentOneTimeFeeChargedRepository", studentOneTimeFeeChargedRepository);
        ReflectionTestUtils.setField(service, "paymentRepository", paymentRepository);
        ReflectionTestUtils.setField(service, "paymentAllocationRepository", paymentAllocationRepository);
        ReflectionTestUtils.setField(service, "allocationRefundRepository", allocationRefundRepository);
        ReflectionTestUtils.setField(service, "studentFeesLineItemRepository", studentFeesLineItemRepository);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        // Default: a fresh StudentFees row has no prior allocations/reversals — tests that
        // care about partial/multi-payment accumulation override these explicitly.
        lenient().when(paymentAllocationRepository.sumAmountPaiseByStudentFeesId(any())).thenReturn(0L);
        lenient().when(allocationRefundRepository.sumAmountPaiseByStudentFeesId(any())).thenReturn(0L);
        // Mimic real JPA save-assigns-id behavior for StudentFees (GenerationType.IDENTITY
        // mutates the SAME entity instance in place — production code relies on reading
        // studentFee.getId() straight after save(), never a reassigned return value, for
        // exactly this reason; line-item persistence below needs a real id to link to).
        lenient().when(studentFeesRepository.save(any(StudentFees.class))).thenAnswer(inv -> {
            StudentFees f = inv.getArgument(0);
            if (f.getId() == null) {
                ReflectionTestUtils.setField(f, "id", nextStudentFeesId++);
            }
            return f;
        });

        // Mimic real JPA save-assigns-id behavior for the Payment recordManualPayment builds
        // internally — markFeesAsPaid now requires payment.getId() to be set (allocations FK
        // to it) before it can allocate anything.
        lenient().when(paymentRepository.save(any(com.indraacademy.ias_management.entity.Payment.class))).thenAnswer(inv -> {
            com.indraacademy.ias_management.entity.Payment p = inv.getArgument(0);
            if (p.getId() == null) {
                ReflectionTestUtils.setField(p, "id", nextPaymentId++);
            }
            return p;
        });

        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        lenient().when(securityUtil.getUsername()).thenReturn("admin");
        lenient().when(securityUtil.getRole()).thenReturn("ADMIN");
        lenient().when(studentOneTimeFeeChargedRepository.findFeeHeadIdBySchoolIdAndStudentId(any(), any()))
                .thenReturn(java.util.Set.of());
        lenient().when(feeCalculationService.parseSession(anyString())).thenAnswer(inv -> {
            String s = inv.getArgument(0);
            String[] parts = s.split("-");
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        });
        lenient().when(feeCalculationService.academicMonthStart(anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(LocalDate.of(2025, 1, 1));
        lenient().when(feeCalculationService.validateFeeConfiguration(any(), any(), any()))
                .thenReturn(FeeCalculationService.FeeConfigurationStatus.ok());
        lenient().when(feeCalculationService.computeMonthSnapshot(
                        any(), any(), any(), any(), anyInt(), anyBoolean(), any(), any(), any(), any()))
                .thenReturn(new FeeCalculationService.MonthSnapshot(
                        BigDecimal.valueOf(2000, 2), BigDecimal.ZERO, BigDecimal.ZERO, "{}", List.of(),
                        SnapshotStatus.COMPUTED, List.of()));
    }

    private void school(int startMonth) {
        School s = new School();
        s.setId(SCHOOL_ID);
        s.setAcademicYearStartMonth(startMonth);
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(s));
    }

    @Test
    void midSessionAdmission_onlyGeneratesRowsFromJoinMonthOnward_noHistoricalCharges() {
        school(4); // April-start
        // Joins September 2025 -> academic month 6. Session "2025-2026".
        service.createDefaultStudentFees("NEW1", "6A", "2025-2026", false, null, LocalDate.of(2025, 9, 15));

        ArgumentCaptor<StudentFees> captor = ArgumentCaptor.forClass(StudentFees.class);
        verify(studentFeesRepository, times(7)).save(captor.capture()); // months 6..12 = 7 rows
        assertThat(captor.getAllValues()).extracting(StudentFees::getMonth)
                .containsExactly(6, 7, 8, 9, 10, 11, 12);
    }

    @Test
    void createDefaultStudentFees_persistsLineItemsLinkedToTheGeneratedRowsRealId() {
        school(4);
        when(feeCalculationService.computeMonthSnapshot(
                        eq(SCHOOL_ID), any(), any(), eq("NEW1"), eq(6), eq(true), any(), any(), any(), any()))
                .thenReturn(new FeeCalculationService.MonthSnapshot(
                        BigDecimal.valueOf(200000, 2), BigDecimal.ZERO, BigDecimal.ZERO, "{}", List.of(),
                        SnapshotStatus.COMPUTED,
                        List.of(new FeeCalculationService.LineItemSnapshot(
                                "FEE_HEAD", 11L, "TUITION", "Tuition Fee", "MONTHLY", 200000L, 0L, null))));

        service.createDefaultStudentFees("NEW1", "6A", "2025-2026", false, null, LocalDate.of(2025, 9, 15));

        ArgumentCaptor<StudentFees> feeCaptor = ArgumentCaptor.forClass(StudentFees.class);
        verify(studentFeesRepository, atLeastOnce()).save(feeCaptor.capture());
        StudentFees firstRow = feeCaptor.getAllValues().get(0);

        ArgumentCaptor<StudentFeesLineItem> liCaptor = ArgumentCaptor.forClass(StudentFeesLineItem.class);
        verify(studentFeesLineItemRepository, atLeastOnce()).save(liCaptor.capture());
        StudentFeesLineItem savedLine = liCaptor.getAllValues().get(0);

        assertThat(savedLine.getStudentFeesId()).isEqualTo(firstRow.getId());
        assertThat(savedLine.getStudentFeesId()).isNotNull();
        assertThat(savedLine.getLineItemType()).isEqualTo(LineItemType.FEE_HEAD);
        assertThat(savedLine.getFeeHeadId()).isEqualTo(11L);
        assertThat(savedLine.getFeeHeadName()).isEqualTo("Tuition Fee");
        assertThat(savedLine.getGrossAmountPaise()).isEqualTo(200000L);
        assertThat(savedLine.getNetAmountPaise()).isEqualTo(200000L);
    }

    @Test
    void midSessionAdmission_onlyTheFirstRowUsesAppliesAtJoin() {
        school(4);
        service.createDefaultStudentFees("NEW1", "6A", "2025-2026", false, null, LocalDate.of(2025, 9, 15));

        ArgumentCaptor<Boolean> isFirstRowCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(feeCalculationService, times(7)).computeMonthSnapshot(
                any(), any(), any(), any(), anyInt(), isFirstRowCaptor.capture(), any(), any(), any(), any());
        List<Boolean> values = isFirstRowCaptor.getAllValues();
        assertThat(values.get(0)).as("month 6, the join month, is the first row").isTrue();
        assertThat(values.subList(1, values.size())).as("every subsequent row is NOT a first row").containsOnly(false);
    }

    @Test
    void januaryStartSchool_midSessionAdmission_joinMonthComputedCorrectly() {
        school(1); // January-start: academic month = calendar month
        // Joins in May 2025 -> academic month 5 for a January-start school.
        service.createDefaultStudentFees("NEW2", "6A", "2025-2026", false, null, LocalDate.of(2025, 5, 1));

        ArgumentCaptor<StudentFees> captor = ArgumentCaptor.forClass(StudentFees.class);
        verify(studentFeesRepository, times(8)).save(captor.capture()); // months 5..12
        assertThat(captor.getAllValues().get(0).getMonth()).isEqualTo(5);
    }

    @Test
    void julyStartSchool_midSessionAdmission_joinMonthComputedCorrectly() {
        school(7);
        // Joins in September 2025 -> academic month 3 for a July-start school (Jul=1,Aug=2,Sep=3).
        service.createDefaultStudentFees("NEW3", "6A", "2025-2026", false, null, LocalDate.of(2025, 9, 1));

        ArgumentCaptor<StudentFees> captor = ArgumentCaptor.forClass(StudentFees.class);
        verify(studentFeesRepository, times(10)).save(captor.capture()); // months 3..12
        assertThat(captor.getAllValues().get(0).getMonth()).isEqualTo(3);
    }

    @Test
    void decemberStartSchool_midSessionAdmission_joinMonthComputedCorrectly() {
        school(12);
        // Joins in February 2026 -> academic month 3 for a December-start school (Dec=1,Jan=2,Feb=3).
        service.createDefaultStudentFees("NEW4", "6A", "2025-2026", false, null, LocalDate.of(2026, 2, 1));

        ArgumentCaptor<StudentFees> captor = ArgumentCaptor.forClass(StudentFees.class);
        verify(studentFeesRepository, times(10)).save(captor.capture()); // months 3..12
        assertThat(captor.getAllValues().get(0).getMonth()).isEqualTo(3);
    }

    @Test
    void annual_midSessionJoinerChargedInFullAtFirstRow() {
        school(4);
        when(feeCalculationService.computeMonthSnapshot(
                        eq(SCHOOL_ID), any(), any(), eq("NEW1"), eq(6), eq(true), any(), any(), any(), any()))
                .thenReturn(new FeeCalculationService.MonthSnapshot(
                        BigDecimal.valueOf(500000, 2), BigDecimal.ZERO, BigDecimal.ZERO, "{}", List.of(),
                        SnapshotStatus.COMPUTED, List.of()));

        service.createDefaultStudentFees("NEW1", "6A", "2025-2026", false, null, LocalDate.of(2025, 9, 15));

        ArgumentCaptor<StudentFees> captor = ArgumentCaptor.forClass(StudentFees.class);
        verify(studentFeesRepository, atLeastOnce()).save(captor.capture());
        StudentFees firstRow = captor.getAllValues().get(0);
        assertThat(firstRow.getMonth()).isEqualTo(6);
        assertThat(firstRow.getBaseAmountDue()).isEqualByComparingTo("5000.00");
    }

    @Test
    void oneTime_newStudentChargedOnceAtFirstRow_recordedInDedupTable() {
        school(4);
        when(feeCalculationService.computeMonthSnapshot(
                        eq(SCHOOL_ID), any(), any(), eq("NEW1"), eq(6), eq(true), any(), any(), any(), any()))
                .thenReturn(new FeeCalculationService.MonthSnapshot(
                        BigDecimal.valueOf(1000000, 2), BigDecimal.ZERO, BigDecimal.ZERO, "{}", List.of(9L),
                        SnapshotStatus.COMPUTED, List.of()));

        service.createDefaultStudentFees("NEW1", "6A", "2025-2026", false, null, LocalDate.of(2025, 9, 15));

        ArgumentCaptor<StudentOneTimeFeeCharged> chargedCaptor = ArgumentCaptor.forClass(StudentOneTimeFeeCharged.class);
        verify(studentOneTimeFeeChargedRepository, times(1)).save(chargedCaptor.capture());
        assertThat(chargedCaptor.getValue().getFeeHeadId()).isEqualTo(9L);
        assertThat(chargedCaptor.getValue().getStudentId()).isEqualTo("NEW1");
    }

    @Test
    void oneTime_alreadyChargedInAPriorSession_notPassedAsNewlyChargedAgain() {
        school(4);
        // Student already has fee head 9 in the dedup table (e.g. from a prior session).
        when(studentOneTimeFeeChargedRepository.findFeeHeadIdBySchoolIdAndStudentId(SCHOOL_ID, "RETURNING1"))
                .thenReturn(java.util.Set.of(9L));

        service.createDefaultStudentFees("RETURNING1", "6A", "2025-2026", false, null, LocalDate.of(2025, 9, 15));

        // The already-charged set must have been passed through to computeMonthSnapshot as-is.
        ArgumentCaptor<java.util.Set<Long>> setCaptor = ArgumentCaptor.forClass(java.util.Set.class);
        verify(feeCalculationService, atLeastOnce()).computeMonthSnapshot(
                any(), any(), any(), any(), anyInt(), anyBoolean(), any(), any(), any(), setCaptor.capture());
        assertThat(setCaptor.getAllValues().get(0)).contains(9L);
    }

    @Test
    void busFee_reflectsTakesBusFlagAtGeneration() {
        school(4);
        when(feeCalculationService.computeMonthSnapshot(
                        any(), any(), any(), any(), anyInt(), anyBoolean(), any(), eq(true), eq(9.0), any()))
                .thenReturn(new FeeCalculationService.MonthSnapshot(
                        BigDecimal.valueOf(2000, 2), BigDecimal.valueOf(65000, 2), BigDecimal.ZERO, "{}", List.of(),
                        SnapshotStatus.COMPUTED, List.of()));

        service.createDefaultStudentFees("BUS1", "6A", "2025-2026", true, 9.0, LocalDate.of(2025, 9, 15));

        ArgumentCaptor<StudentFees> captor = ArgumentCaptor.forClass(StudentFees.class);
        verify(studentFeesRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getBusFeeDue()).isEqualByComparingTo("650.00");
    }

    @Test
    void busFee_zeroWhenStudentDoesNotTakeBusAtGeneration() {
        school(4);
        // Default stub already returns busFeeDue=ZERO; takesBus=false is passed straight through.
        service.createDefaultStudentFees("NOBUS1", "6A", "2025-2026", false, null, LocalDate.of(2025, 9, 15));

        ArgumentCaptor<StudentFees> captor = ArgumentCaptor.forClass(StudentFees.class);
        verify(studentFeesRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getBusFeeDue()).isEqualByComparingTo("0.00");
        assertThat(captor.getAllValues().get(0).getTakesBus()).isFalse();
    }

    // ─── Hardening: never persist a snapshot when we cannot confidently calculate it ─────

    @Test
    void missingAcademicSession_refusesToRegisterFees_noPartialRowsCreated() {
        school(4);
        when(feeCalculationService.validateFeeConfiguration(eq(SCHOOL_ID), eq("2025-2026"), eq("6A")))
                .thenReturn(FeeCalculationService.FeeConfigurationStatus.fail(
                        "AcademicSession not found for schoolId=1, session='2025-2026'"));

        assertThatThrownBy(() ->
                service.createDefaultStudentFees("NEW1", "6A", "2025-2026", false, null, LocalDate.of(2025, 9, 15)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NEW1")
                .hasMessageContaining("AcademicSession");

        verify(studentFeesRepository, never()).save(any());
        verify(feeCalculationService, never()).computeMonthSnapshot(
                any(), any(), any(), any(), anyInt(), anyBoolean(), any(), any(), any(), any());
        verify(studentOneTimeFeeChargedRepository, never()).save(any());
    }

    @Test
    void missingFeeRules_refusesToRegisterFees_noPartialRowsCreated() {
        school(4);
        when(feeCalculationService.validateFeeConfiguration(eq(SCHOOL_ID), eq("2025-2026"), eq("6A")))
                .thenReturn(FeeCalculationService.FeeConfigurationStatus.fail(
                        "No FeeStructureRule configured for schoolId=1, session='2025-2026', className='6A'"));

        assertThatThrownBy(() ->
                service.createDefaultStudentFees("NEW1", "6A", "2025-2026", false, null, LocalDate.of(2025, 9, 15)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FeeStructureRule");

        verify(studentFeesRepository, never()).save(any());
    }

    // ─── markFeesAsPaid — consumer migration: snapshot-first, never silent-zero-on-unknown ─

    private StudentFees existingRow(int month, BigDecimal baseAmountDue, BigDecimal busFeeDue, BigDecimal discountAmount) {
        StudentFees fee = new StudentFees();
        fee.setStudentId("S1");
        fee.setSchoolId(SCHOOL_ID);
        fee.setYear("2025-2026");
        fee.setMonth(month);
        fee.setPaid(false);
        fee.setBaseAmountDue(baseAmountDue);
        fee.setBusFeeDue(busFeeDue);
        fee.setDiscountAmount(discountAmount);
        fee.setSnapshotStatus(baseAmountDue != null ? SnapshotStatus.COMPUTED : null);
        ReflectionTestUtils.setField(fee, "id", 100L + month);
        // markFeesAsPaid/refund reversal use the row-locking finder; computeCheckoutQuote and
        // the manual-payment bucket-computation pass use the plain one — stub both so any
        // caller in this test file finds the same row.
        lenient().when(studentFeesRepository.findByStudentIdAndSchoolIdAndYearAndMonth("S1", SCHOOL_ID, "2025-2026", month))
                .thenReturn(fee);
        lenient().when(studentFeesRepository.findByStudentIdAndSchoolIdAndYearAndMonthForUpdate("S1", SCHOOL_ID, "2025-2026", month))
                .thenReturn(fee);
        return fee;
    }

    private static long nextPaymentId = 500L;
    private static long nextStudentFeesId = 9000L;

    private com.indraacademy.ias_management.entity.Payment payment(String monthSelectionString, int amountPaise) {
        com.indraacademy.ias_management.entity.Payment p = new com.indraacademy.ias_management.entity.Payment();
        p.setStudentId("S1");
        p.setSession("2025-2026");
        p.setMonth(monthSelectionString);
        p.setClassName("6A");
        p.setAmount(amountPaise);
        p.setAdditionalCharges(0);
        ReflectionTestUtils.setField(p, "id", nextPaymentId++);
        return p;
    }

    @Test
    void markFeesAsPaid_readsStoredSnapshotDirectly_neverRecomputesWhenTrustworthy() {
        StudentFees fee = existingRow(1, BigDecimal.valueOf(2000), BigDecimal.valueOf(800), BigDecimal.ZERO);
        // month 1 selected -> bit index 0
        String months = "100000000000";
        // Generous payment (well beyond schoolFeeDue + any possible late-fee tier for one
        // month) so this test doesn't depend on today's exact date relative to academic
        // month 1 — calculateLateFees is a real, unmocked private method computed live.
        com.indraacademy.ias_management.entity.Payment p = payment(months, 100_000_00);
        when(feeCalculationService.resolveSchoolFeeDue(fee, SCHOOL_ID, "2025-2026")).thenReturn(Optional.of(BigDecimal.valueOf(2800)));

        service.markFeesAsPaid(p);

        verify(feeCalculationService, never()).loadActiveRules(any(), any(), any());
        verify(feeCalculationService, never()).calculateMonthFeeRupees(anyInt(), any());
        assertThat(fee.getPaid()).isTrue();
        // Immutability/no-coupling: paying a month never creates, reads, or otherwise
        // touches its line items — they were written once at generation time and payment
        // allocation targets the StudentFees row only, never the line-item table.
        verifyNoInteractions(studentFeesLineItemRepository);
    }

    @Test
    void markFeesAsPaid_refusesPaymentWhenAnySelectedMonthIsUnresolvable() {
        StudentFees fee = existingRow(1, null, null, null); // no snapshot
        String months = "100000000000";
        com.indraacademy.ias_management.entity.Payment p = payment(months, 500_000);
        when(feeCalculationService.resolveSchoolFeeDue(fee, SCHOOL_ID, "2025-2026")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markFeesAsPaid(p))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("S1")
                .hasMessageContaining("month 1");

        verify(studentFeesRepository, never()).save(any());
        assertThat(fee.getPaid()).isNotEqualTo(true);
    }

    // ─── Allocation ledger — exact per-month accounting (this phase) ────────────────────

    @Test
    void markFeesAsPaid_onePayment_coversMultipleMonths_allocatesSeparatelyToEach() {
        StudentFees m1 = existingRow(1, BigDecimal.valueOf(2000), BigDecimal.ZERO, BigDecimal.ZERO);
        StudentFees m2 = existingRow(2, BigDecimal.valueOf(1500), BigDecimal.ZERO, BigDecimal.ZERO);
        when(feeCalculationService.resolveSchoolFeeDue(m1, SCHOOL_ID, "2025-2026")).thenReturn(Optional.of(BigDecimal.valueOf(2000)));
        when(feeCalculationService.resolveSchoolFeeDue(m2, SCHOOL_ID, "2025-2026")).thenReturn(Optional.of(BigDecimal.valueOf(1500)));
        // Generous: comfortably covers both months' due + any possible late fee on either.
        Payment p = payment("110000000000", 500_000); // ₹5000

        service.markFeesAsPaid(p);

        assertThat(m1.getPaid()).isTrue();
        assertThat(m2.getPaid()).isTrue();
        ArgumentCaptor<PaymentStudentFeesAllocation> captor = ArgumentCaptor.forClass(PaymentStudentFeesAllocation.class);
        verify(paymentAllocationRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(PaymentStudentFeesAllocation::getMonth).containsExactly(1, 2);
        assertThat(captor.getAllValues()).allSatisfy(a -> assertThat(a.getPaymentId()).isEqualTo(p.getId()));
    }

    @Test
    void multiplePaymentsAgainstSameMonth_secondPaymentAccumulatesOnTopOfFirstsNetAllocation() {
        StudentFees fee = existingRow(1, BigDecimal.valueOf(2000), BigDecimal.ZERO, BigDecimal.ZERO);
        when(feeCalculationService.resolveSchoolFeeDue(fee, SCHOOL_ID, "2025-2026")).thenReturn(Optional.of(BigDecimal.valueOf(2000)));

        // First payment: a small partial amount — not enough to fully cover the month.
        Payment first = payment("100000000000", 80_000); // ₹800
        service.markFeesAsPaid(first);
        assertThat(fee.getPaid()).isFalse();
        assertThat(fee.getAmountPaid()).isEqualByComparingTo("800");

        // The row now has ₹800 already net-allocated from the first payment — the mock ledger
        // must reflect that for the second payment's "already allocated" computation.
        when(paymentAllocationRepository.sumAmountPaiseByStudentFeesId(fee.getId())).thenReturn(80_000L);

        // Second payment: a large top-up, comfortably exceeding the remaining due + any late
        // fee, so the row becomes definitively fully paid regardless of today's exact late
        // fee tier.
        Payment second = payment("100000000000", 500_000); // ₹5000
        service.markFeesAsPaid(second);

        assertThat(fee.getPaid()).isTrue();
        assertThat(fee.getAmountPaid()).isEqualByComparingTo("5800"); // 800 + 5000 net allocated
        verify(paymentAllocationRepository, times(2)).save(any(PaymentStudentFeesAllocation.class));
    }

    @Test
    void markFeesAsPaid_noMatchingStudentFeesRowForAnySelectedMonth_refusesRatherThanSilentlySucceeding() {
        // No existingRow() set up at all — every month lookup returns null.
        Payment p = payment("100000000000", 200_000);

        assertThatThrownBy(() -> service.markFeesAsPaid(p))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No StudentFees records found");

        verify(paymentAllocationRepository, never()).save(any());
    }

    @Test
    void markFeesAsPaid_allSelectedMonthsAlreadyFullyPaid_refusesToAllocateWithNoTarget() {
        StudentFees fee = existingRow(1, BigDecimal.valueOf(2000), BigDecimal.ZERO, BigDecimal.ZERO);
        when(feeCalculationService.resolveSchoolFeeDue(fee, SCHOOL_ID, "2025-2026")).thenReturn(Optional.of(BigDecimal.valueOf(2000)));
        // Already far more than fully covered by prior allocations.
        when(paymentAllocationRepository.sumAmountPaiseByStudentFeesId(fee.getId())).thenReturn(1_000_000L);
        Payment p = payment("100000000000", 50_000);

        assertThatThrownBy(() -> service.markFeesAsPaid(p))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already fully paid");

        verify(paymentAllocationRepository, never()).save(any());
    }

    @Test
    void markFeesAsPaid_locksEachSelectedStudentFeesRowForUpdate_toSerializeConcurrentPayments() {
        StudentFees fee = existingRow(1, BigDecimal.valueOf(2000), BigDecimal.ZERO, BigDecimal.ZERO);
        when(feeCalculationService.resolveSchoolFeeDue(fee, SCHOOL_ID, "2025-2026")).thenReturn(Optional.of(BigDecimal.valueOf(2000)));
        Payment p = payment("100000000000", 500_000);

        service.markFeesAsPaid(p);

        // The row-locking finder — not the plain one — is what markFeesAsPaid reads before
        // mutating a row; this is what makes a second concurrent payment against the same
        // month block until this transaction commits, instead of both reading stale state.
        verify(studentFeesRepository).findByStudentIdAndSchoolIdAndYearAndMonthForUpdate("S1", SCHOOL_ID, "2025-2026", 1);
    }

    @Test
    void markFeesAsPaid_unpersistedPaymentRejected_allocationsRequireARealPaymentId() {
        Payment p = new Payment(); // no id set — simulates a caller forgetting to persist first
        p.setStudentId("S1");
        p.setSession("2025-2026");
        p.setMonth("100000000000");
        p.setAmount(500_000);

        assertThatThrownBy(() -> service.markFeesAsPaid(p))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("persisted");

        verify(paymentAllocationRepository, never()).save(any());
    }

    // ─── computeCheckoutQuote — backend-authoritative school fee + late fee + platform fee ─

    @Test
    void computeCheckoutQuote_sumsSchoolFeeDueAcrossMonths_addsLateFeeAndPlatformFeeOnTop() {
        StudentFees m1 = existingRow(1, BigDecimal.valueOf(2000), BigDecimal.ZERO, BigDecimal.ZERO);
        StudentFees m2 = existingRow(2, BigDecimal.valueOf(2000), BigDecimal.ZERO, BigDecimal.ZERO);
        when(feeCalculationService.resolveSchoolFeeDue(m1, SCHOOL_ID, "2025-2026")).thenReturn(Optional.of(BigDecimal.valueOf(2000)));
        when(feeCalculationService.resolveSchoolFeeDue(m2, SCHOOL_ID, "2025-2026")).thenReturn(Optional.of(BigDecimal.valueOf(2000)));

        CheckoutQuoteDto quote = service.computeCheckoutQuote("S1", "2025-2026", List.of(1, 2));

        assertThat(quote.getSchoolFeeDue()).isEqualByComparingTo("4000");
        assertThat(quote.getUnresolvedMonths()).isEmpty();
        // platformFee = ceil((schoolFeeDue + lateFee) * 0.015); lateFee is 0 here since the
        // mocked "today" context makes these months not overdue in this unit test's scope —
        // what matters is totalAmount = schoolFeeDue + lateFee + platformFee, consistently.
        assertThat(quote.getTotalAmount()).isEqualByComparingTo(
                quote.getSchoolFeeDue().add(quote.getLateFee()).add(quote.getPlatformFee()));
    }

    @Test
    void computeCheckoutQuote_marksUnresolvedMonthsSeparately_excludesThemFromTotals() {
        StudentFees resolvable = existingRow(1, BigDecimal.valueOf(2000), BigDecimal.ZERO, BigDecimal.ZERO);
        when(feeCalculationService.resolveSchoolFeeDue(resolvable, SCHOOL_ID, "2025-2026")).thenReturn(Optional.of(BigDecimal.valueOf(2000)));
        // Month 2 has no StudentFees row at all.
        when(studentFeesRepository.findByStudentIdAndSchoolIdAndYearAndMonth("S1", SCHOOL_ID, "2025-2026", 2)).thenReturn(null);

        CheckoutQuoteDto quote = service.computeCheckoutQuote("S1", "2025-2026", List.of(1, 2));

        assertThat(quote.getUnresolvedMonths()).containsExactly(2);
        assertThat(quote.getSchoolFeeDue()).isEqualByComparingTo("2000"); // only the resolvable month
    }

    /**
     * Real incident this covers: a month with a partial-then-refunded payment (paid stays
     * false — see PaymentService.recomputeStudentFeesNetState — until amountPaid covers the
     * full due) used to be quoted at its FULL original amount again by both the checkout
     * screen and PaymentController.createOrder's Razorpay order amount — a genuine
     * double-charge risk, not just a fee-reminder display bug. schoolFeeDue must reflect only
     * the REMAINING balance: grossDue − netAmountPaid.
     */
    @Test
    void computeCheckoutQuote_subtractsNetAmountPaid_fromSchoolFeeDue_forAPartiallyPaidUnpaidMonth() {
        StudentFees m1 = existingRow(1, BigDecimal.valueOf(17000), BigDecimal.valueOf(800), BigDecimal.ZERO);
        m1.setAmountPaid(BigDecimal.valueOf(16433)); // gross 18433 paid, then a 2000 refund netted to 16433
        when(feeCalculationService.resolveSchoolFeeDue(m1, SCHOOL_ID, "2025-2026")).thenReturn(Optional.of(BigDecimal.valueOf(17800)));

        CheckoutQuoteDto quote = service.computeCheckoutQuote("S1", "2025-2026", List.of(1));

        assertThat(quote.getSchoolFeeDue()).isEqualByComparingTo("1367"); // 17800 - 16433, never the full 17800 again
    }

    @Test
    void computeCheckoutQuote_neverGoesNegative_whenNetAmountPaidExceedsSchoolFeeDueAlone() {
        // amountPaid (e.g. it included the late fee/platform fee portion of an earlier
        // payment) can legitimately exceed the bare schoolFeeDue for the row — schoolFeeDue's
        // contribution must floor at zero, not go negative.
        StudentFees m1 = existingRow(1, BigDecimal.valueOf(2000), BigDecimal.ZERO, BigDecimal.ZERO);
        m1.setAmountPaid(BigDecimal.valueOf(2500));
        when(feeCalculationService.resolveSchoolFeeDue(m1, SCHOOL_ID, "2025-2026")).thenReturn(Optional.of(BigDecimal.valueOf(2000)));

        CheckoutQuoteDto quote = service.computeCheckoutQuote("S1", "2025-2026", List.of(1));

        assertThat(quote.getSchoolFeeDue()).isEqualByComparingTo("0");
    }

    // ─── getMonthFeeBreakdown — Phase 3 frontend/read-model line-item breakdown ─────────

    private StudentFeesLineItem lineItem(Long studentFeesId, LineItemType type, String feeHeadCode,
                                          String feeHeadName, long grossPaise, long discountPaise, String discountConfigType) {
        StudentFeesLineItem li = new StudentFeesLineItem();
        li.setStudentFeesId(studentFeesId);
        li.setSchoolId(SCHOOL_ID);
        li.setStudentId("S1");
        li.setSession("2025-2026");
        li.setMonth(1);
        li.setLineItemType(type);
        li.setFeeHeadCode(feeHeadCode);
        li.setFeeHeadName(feeHeadName);
        li.setGrossAmountPaise(grossPaise);
        li.setDiscountAmountPaise(discountPaise);
        li.setNetAmountPaise(grossPaise - discountPaise);
        li.setDiscountConfigType(discountConfigType);
        return li;
    }

    @Test
    void getMonthFeeBreakdown_lineItemsPresent_reconcilesExactlyWithSchoolFeeDueAndMarksAvailable() {
        StudentFees fee = existingRow(1, BigDecimal.valueOf(2400), BigDecimal.valueOf(800), BigDecimal.valueOf(400));
        when(feeCalculationService.resolveSchoolFeeDue(fee, SCHOOL_ID, "2025-2026")).thenReturn(Optional.of(BigDecimal.valueOf(3200)));
        when(studentFeesLineItemRepository.findByStudentFeesIdAndSupersededAtIsNullOrderById(fee.getId())).thenReturn(List.of(
                lineItem(fee.getId(), LineItemType.FEE_HEAD, "TUITION", "Tuition Fee", 280000L, 40000L, "DISCOUNT_FIXED"),
                lineItem(fee.getId(), LineItemType.BUS, null, "Bus Fee", 80000L, 0L, null)
        ));

        MonthFeeBreakdownDto dto = service.getMonthFeeBreakdown("S1", "2025-2026", 1).orElseThrow();

        assertThat(dto.isLineItemBreakdownAvailable()).isTrue();
        assertThat(dto.getLineItems()).hasSize(2);
        assertThat(dto.getLineItems().get(0).getGrossAmount()).isEqualByComparingTo("2800");
        assertThat(dto.getLineItems().get(0).getDiscountAmount()).isEqualByComparingTo("400");
        assertThat(dto.getLineItems().get(0).getNetAmount()).isEqualByComparingTo("2400");
        assertThat(dto.getLineItems().get(1).getNetAmount()).isEqualByComparingTo("800");

        // The exact reconciliation invariant: SUM(lineItems.netAmount) == schoolFeeDue.
        BigDecimal lineItemTotal = dto.getLineItems().stream()
                .map(li -> li.getNetAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(lineItemTotal).isEqualByComparingTo(dto.getSchoolFeeDue());
    }

    @Test
    void getMonthFeeBreakdown_historicalRowWithNoLineItems_neverFabricatesButKeepsTrustedTotal() {
        StudentFees fee = existingRow(1, BigDecimal.valueOf(2000), BigDecimal.ZERO, BigDecimal.ZERO);
        when(feeCalculationService.resolveSchoolFeeDue(fee, SCHOOL_ID, "2025-2026")).thenReturn(Optional.of(BigDecimal.valueOf(2000)));
        when(studentFeesLineItemRepository.findByStudentFeesIdAndSupersededAtIsNullOrderById(fee.getId())).thenReturn(List.of());

        MonthFeeBreakdownDto dto = service.getMonthFeeBreakdown("S1", "2025-2026", 1).orElseThrow();

        assertThat(dto.isLineItemBreakdownAvailable()).isFalse();
        assertThat(dto.getLineItems()).isEmpty();
        assertThat(dto.getSchoolFeeDue()).isEqualByComparingTo("2000");
    }

    @Test
    void getMonthFeeBreakdown_totalGenuinelyUnknown_schoolFeeDueIsNullNeverZero() {
        StudentFees fee = existingRow(1, null, null, BigDecimal.ZERO);
        when(feeCalculationService.resolveSchoolFeeDue(fee, SCHOOL_ID, "2025-2026")).thenReturn(Optional.empty());
        when(studentFeesLineItemRepository.findByStudentFeesIdAndSupersededAtIsNullOrderById(fee.getId())).thenReturn(List.of());

        MonthFeeBreakdownDto dto = service.getMonthFeeBreakdown("S1", "2025-2026", 1).orElseThrow();

        assertThat(dto.isLineItemBreakdownAvailable()).isFalse();
        assertThat(dto.getSchoolFeeDue()).isNull();
    }

    @Test
    void getMonthFeeBreakdown_noStudentFeesRow_returnsEmpty() {
        when(studentFeesRepository.findByStudentIdAndSchoolIdAndYearAndMonth("S1", SCHOOL_ID, "2025-2026", 5)).thenReturn(null);

        assertThat(service.getMonthFeeBreakdown("S1", "2025-2026", 5)).isEmpty();
        verifyNoInteractions(studentFeesLineItemRepository);
    }

    // ─── recordManualPayment — backend-authoritative manual payment flow ────────────────

    private ManualPaymentRequest manualRequest(String monthSelectionString, BigDecimal amountReceived, String mode, String reference) {
        ManualPaymentRequest req = new ManualPaymentRequest();
        req.setStudentId("S1");
        req.setStudentName("Student One");
        req.setClassName("6A");
        req.setSession("2025-2026");
        req.setMonthSelectionString(monthSelectionString);
        req.setAmountReceived(amountReceived);
        req.setPaymentMode(mode);
        req.setReferenceNumber(reference);
        return req;
    }

    @Test
    void recordManualPayment_validPayment_marksMonthPaidAndSavesPaymentWithManualFlag() {
        StudentFees fee = existingRow(1, BigDecimal.valueOf(2000), BigDecimal.ZERO, BigDecimal.ZERO);
        when(feeCalculationService.resolveSchoolFeeDue(fee, SCHOOL_ID, "2025-2026")).thenReturn(Optional.of(BigDecimal.valueOf(2000)));
        // Generous amount (schoolFeeDue + comfortable margin over any possible late-fee tier
        // for one month) so this test doesn't depend on today's date relative to month 1 —
        // calculateLateFees is a real, unmocked private method computed live.
        ManualPaymentRequest req = manualRequest("100000000000", BigDecimal.valueOf(3000), "CASH", "RCPT-1");
        // manuallyPaid/manualPaymentReceived are now derived from a ledger join
        // (allocation -> payment.manualPaymentMode) that Mockito can't execute for real —
        // stub the derived sum directly to reflect "this row's allocation came from a
        // manual payment."
        when(paymentAllocationRepository.sumManualAmountPaiseByStudentFeesId(fee.getId())).thenReturn(300_000L);

        Payment result = service.recordManualPayment(req, "127.0.0.1");

        assertThat(fee.getPaid()).isTrue();
        assertThat(fee.getManuallyPaid()).isTrue();
        verify(paymentRepository).save(any(Payment.class));
        verify(auditService).log(eq("admin"), eq("ADMIN"), eq("RECORD_MANUAL_PAYMENT"), eq("Payment"), anyString(), isNull(), anyString(), eq("127.0.0.1"));
        assertThat(result.isPaidManually()).isTrue();
        assertThat(result.getManualPaymentMode()).isEqualTo("CASH");
        assertThat(result.getManualReferenceNumber()).isEqualTo("RCPT-1");
    }

    @Test
    void recordManualPayment_usesLockedReadForEveryTargetRow_neverTheUnlockedOne() {
        // Regression test for a real, reproduced payment-vs-recalculation lost-update race:
        // recordManualPayment must take the PESSIMISTIC_WRITE lock on the very first read of
        // each target row (for the legacy display-bucket computation), not an unlocked read
        // followed by markFeesAsPaid's later locked re-fetch. Hibernate's persistence-context
        // identity map returns the SAME managed instance for both reads within one
        // transaction — an earlier unlocked read seeds it with whatever was committed at that
        // moment, and the later "locked" re-fetch silently reuses that stale copy instead of
        // refreshing it (the lock is genuinely acquired at the DB level, but the Java object's
        // fields are never repopulated) — so a concurrent recalculation committed in between
        // gets clobbered when this method's own save() writes back the whole (stale) entity.
        // See the fix-site comment in StudentFeesService.recordManualPayment for the full
        // interleaving that was actually observed.
        StudentFees fee = existingRow(1, BigDecimal.valueOf(2000), BigDecimal.ZERO, BigDecimal.ZERO);
        when(feeCalculationService.resolveSchoolFeeDue(fee, SCHOOL_ID, "2025-2026")).thenReturn(Optional.of(BigDecimal.valueOf(2000)));
        ManualPaymentRequest req = manualRequest("100000000000", BigDecimal.valueOf(3000), "CASH", "RCPT-LOCK-1");
        when(paymentAllocationRepository.sumManualAmountPaiseByStudentFeesId(fee.getId())).thenReturn(300_000L);

        service.recordManualPayment(req, "127.0.0.1");

        verify(studentFeesRepository, atLeastOnce())
                .findByStudentIdAndSchoolIdAndYearAndMonthForUpdate("S1", SCHOOL_ID, "2025-2026", 1);
        verify(studentFeesRepository, never())
                .findByStudentIdAndSchoolIdAndYearAndMonth(anyString(), any(), anyString(), anyInt());
    }

    @Test
    void recordManualPayment_validPayment_setsAmountPaidAndManualPaymentReceivedOnRow_soItNoLongerShowsOverdue() {
        StudentFees fee = existingRow(3, BigDecimal.valueOf(1500), BigDecimal.valueOf(500), BigDecimal.ZERO);
        when(feeCalculationService.resolveSchoolFeeDue(fee, SCHOOL_ID, "2025-2026")).thenReturn(Optional.of(BigDecimal.valueOf(2000)));
        // Generous amount — see comment in the test above re: live calculateLateFees.
        ManualPaymentRequest req = manualRequest("001000000000", BigDecimal.valueOf(3000), "UPI", "UTR-99");
        // Tie the derived "manual net" sum to whatever this payment actually allocates (its
        // exact value depends on today's live late-fee calculation) so amountPaid and
        // manualPaymentReceived — both ultimately derived from the ledger — end up equal for
        // this single-manual-payment row, without hardcoding a guessed amount.
        long[] capturedAllocationPaise = new long[1];
        doAnswer(inv -> {
            PaymentStudentFeesAllocation a = inv.getArgument(0);
            capturedAllocationPaise[0] = a.getAmountPaise();
            return a;
        }).when(paymentAllocationRepository).save(any(PaymentStudentFeesAllocation.class));
        when(paymentAllocationRepository.sumManualAmountPaiseByStudentFeesId(fee.getId()))
                .thenAnswer(inv -> capturedAllocationPaise[0]);

        service.recordManualPayment(req, "10.0.0.5");

        assertThat(fee.getPaid()).isTrue();
        // amountPaid = resolveSchoolFeeDue (2000) + this month's live late fee — not asserted
        // exactly since it depends on today's date; what matters for "no longer overdue" is
        // that both fields were populated identically (manuallyPaid=true semantics) and paid=true.
        assertThat(fee.getAmountPaid()).isGreaterThanOrEqualTo(BigDecimal.valueOf(2000));
        assertThat(fee.getManualPaymentReceived()).isEqualByComparingTo(fee.getAmountPaid());
    }

    @Test
    void recordManualPayment_amountBelowComputedTotal_isAcceptedAsAPartialPayment_rowStaysUnpaid() {
        // Exact-allocation accounting (this phase) replaced the old 90%-of-total shortcut: a
        // payment that falls short of a month's due is no longer rejected outright — it's
        // allocated exactly, and the row is left unpaid (not fabricated as fully paid) since
        // its net allocation doesn't yet cover what's owed.
        StudentFees fee = existingRow(1, BigDecimal.valueOf(2000), BigDecimal.ZERO, BigDecimal.ZERO);
        when(feeCalculationService.resolveSchoolFeeDue(fee, SCHOOL_ID, "2025-2026")).thenReturn(Optional.of(BigDecimal.valueOf(2000)));
        ManualPaymentRequest req = manualRequest("100000000000", BigDecimal.valueOf(100), "CASH", null); // far short of 2000

        service.recordManualPayment(req, "127.0.0.1");

        assertThat(fee.getPaid()).isFalse();
        assertThat(fee.getAmountPaid()).isEqualByComparingTo("100");
        verify(paymentAllocationRepository).save(argThat(a -> a.getAmountPaise() == 10_000L));
    }

    @Test
    void recordManualPayment_unresolvedMonth_rejectedWithNoMutation() {
        StudentFees fee = existingRow(1, null, null, null); // no trustworthy snapshot
        when(feeCalculationService.resolveSchoolFeeDue(fee, SCHOOL_ID, "2025-2026")).thenReturn(Optional.empty());
        ManualPaymentRequest req = manualRequest("100000000000", BigDecimal.valueOf(2000), "CASH", null);

        assertThatThrownBy(() -> service.recordManualPayment(req, "127.0.0.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("month 1");

        assertThat(fee.getPaid()).isNotEqualTo(true);
    }

    @Test
    void recordManualPayment_duplicateReferenceNumber_rejectedBeforeTouchingStudentFees() {
        when(paymentRepository.existsByManualReferenceNumberAndSchoolId("RCPT-1", SCHOOL_ID)).thenReturn(true);
        ManualPaymentRequest req = manualRequest("100000000000", BigDecimal.valueOf(2000), "CASH", "RCPT-1");

        assertThatThrownBy(() -> service.recordManualPayment(req, "127.0.0.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RCPT-1");

        verify(paymentRepository, never()).save(any());
        verify(studentFeesRepository, never()).findByStudentIdAndSchoolIdAndYearAndMonth(any(), any(), any(), anyInt());
    }

    @Test
    void recordManualPayment_crossSchoolStudentId_treatedAsUnresolvedNeverLeaksAnotherSchoolsRow() {
        // schoolId always comes from securityUtil (the admin's own JWT-derived school), never
        // from the request — a studentId belonging to a different school simply has no row
        // under THIS schoolId, so it resolves to "unknown" and is refused, exactly like any
        // other unresolved month. It must never fall back to trusting a client-supplied
        // schoolId.
        when(studentFeesRepository.findByStudentIdAndSchoolIdAndYearAndMonthForUpdate("OTHER_SCHOOL_STUDENT", SCHOOL_ID, "2025-2026", 1))
                .thenReturn(null);
        ManualPaymentRequest req = manualRequest("100000000000", BigDecimal.valueOf(2000), "CASH", null);
        req.setStudentId("OTHER_SCHOOL_STUDENT");

        assertThatThrownBy(() -> service.recordManualPayment(req, "127.0.0.1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void recordManualPayment_rejectedPayment_neverWritesTheManualPaymentAuditEntry() {
        // Mockito mocks don't roll back a DB the way @Transactional does at runtime, but this
        // still verifies the code path never reaches the "success" audit write when
        // markFeesAsPaid refuses the payment — the observable half of "succeed/fail
        // together" this test suite can assert without a real transactional container.
        StudentFees fee = existingRow(1, null, null, null); // no trustworthy snapshot -> unresolved
        when(feeCalculationService.resolveSchoolFeeDue(fee, SCHOOL_ID, "2025-2026")).thenReturn(Optional.empty());
        ManualPaymentRequest req = manualRequest("100000000000", BigDecimal.valueOf(2000), "CASH", null);

        assertThatThrownBy(() -> service.recordManualPayment(req, "127.0.0.1")).isInstanceOf(IllegalStateException.class);

        verify(auditService, never()).log(anyString(), anyString(), eq("RECORD_MANUAL_PAYMENT"), any(), any(), any(), any(), any());
    }

    @Test
    void recordManualPayment_invalidPaymentMode_rejectedBeforeAnyPersistence() {
        ManualPaymentRequest req = manualRequest("100000000000", BigDecimal.valueOf(2000), "BITCOIN", null);

        assertThatThrownBy(() -> service.recordManualPayment(req, "127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(paymentRepository, never()).save(any());
    }

    @Test
    void recordManualPayment_referenceRaceSlipsPastAppCheck_dbConstraintViolationHandledGracefully() {
        // The existsByManualReferenceNumberAndSchoolId check can race between two concurrent
        // requests; the DB unique index on payment(school_id, manual_reference_number)
        // (added this integrity phase) is the real backstop. Simulate that race by having
        // the app-level check pass (false) but the save itself throw the constraint violation.
        existingRow(1, BigDecimal.valueOf(2000), BigDecimal.ZERO, BigDecimal.ZERO);
        when(paymentRepository.existsByManualReferenceNumberAndSchoolId("RCPT-RACE", SCHOOL_ID)).thenReturn(false);
        when(paymentRepository.save(any(Payment.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key value violates unique constraint \"uq_payment_school_manual_reference\""));
        ManualPaymentRequest req = manualRequest("100000000000", BigDecimal.valueOf(2000), "CASH", "RCPT-RACE");

        assertThatThrownBy(() -> service.recordManualPayment(req, "127.0.0.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RCPT-RACE");

        verify(paymentAllocationRepository, never()).save(any());
    }

    // ─── createDefaultStudentFees — duplicate-registration guard (new integrity phase) ───

    @Test
    void createDefaultStudentFees_rowsAlreadyExistForStudentYear_refusesRatherThanDuplicating() {
        when(studentFeesRepository.existsByStudentIdAndYearAndSchoolId("NEW1", "2025-2026", SCHOOL_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.createDefaultStudentFees(
                "NEW1", "6A", "2025-2026", false, null, LocalDate.of(2025, 9, 15)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NEW1");

        verify(studentFeesRepository, never()).save(any());
        verify(feeCalculationService, never()).computeMonthSnapshot(
                any(), any(), any(), any(), anyInt(), anyBoolean(), any(), any(), any(), any());
    }

    // ─── updateStudentFees — financial-field bypass closed (new integrity phase) ─────────

    @Test
    void updateStudentFees_cannotSetPaidOrAmountOrSnapshotFields_onlyNonFinancialFieldsChange() {
        StudentFees existing = new StudentFees();
        ReflectionTestUtils.setField(existing, "id", 55L);
        existing.setSchoolId(SCHOOL_ID);
        existing.setStudentId("S1");
        existing.setPaid(false);
        existing.setAmountPaid(null);
        existing.setBaseAmountDue(BigDecimal.valueOf(2000));
        existing.setClassName("5A");
        when(studentFeesRepository.findById(55L)).thenReturn(Optional.of(existing));

        StudentFeesAdminUpdateRequest request = new StudentFeesAdminUpdateRequest();
        request.setId(55L);
        request.setClassName("6B");
        request.setTakesBus(true);
        request.setDistance(12.5);

        StudentFees saved = service.updateStudentFees(request);

        // Only the non-financial fields the DTO exposes changed.
        assertThat(saved.getClassName()).isEqualTo("6B");
        assertThat(saved.getTakesBus()).isTrue();
        assertThat(saved.getDistance()).isEqualTo(12.5);
        // Financial/snapshot state is exactly what it was before — this request had no way
        // to express a change to it even if it had tried (StudentFeesAdminUpdateRequest has
        // no paid/amountPaid/baseAmountDue fields at all).
        assertThat(saved.getPaid()).isFalse();
        assertThat(saved.getAmountPaid()).isNull();
        assertThat(saved.getBaseAmountDue()).isEqualByComparingTo("2000");
    }

    @Test
    void updateStudentFees_crossSchoolRecord_rejected() {
        StudentFees existing = new StudentFees();
        ReflectionTestUtils.setField(existing, "id", 56L);
        existing.setSchoolId(2L); // different school than SCHOOL_ID(1L)
        when(studentFeesRepository.findById(56L)).thenReturn(Optional.of(existing));

        StudentFeesAdminUpdateRequest request = new StudentFeesAdminUpdateRequest();
        request.setId(56L);
        request.setClassName("6B");

        assertThatThrownBy(() -> service.updateStudentFees(request))
                .isInstanceOf(SecurityException.class);

        verify(studentFeesRepository, never()).save(any());
    }

    @Test
    void updateStudentFees_recordNotFound_rejected() {
        when(studentFeesRepository.findById(999L)).thenReturn(Optional.empty());
        StudentFeesAdminUpdateRequest request = new StudentFeesAdminUpdateRequest();
        request.setId(999L);

        assertThatThrownBy(() -> service.updateStudentFees(request))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    // ─── createStudentFees — server-computed snapshot, never client-supplied ────────────

    @Test
    void createStudentFees_computesSnapshotServerSide_startsUnpaid() {
        school(4);
        when(studentFeesRepository.findByStudentIdAndSchoolIdAndYearAndMonth("NEW1", SCHOOL_ID, "2025-2026", 6))
                .thenReturn(null);
        when(feeCalculationService.validateFeeConfiguration(SCHOOL_ID, "2025-2026", "6A"))
                .thenReturn(FeeCalculationService.FeeConfigurationStatus.ok());

        when(studentFeesRepository.save(any(StudentFees.class))).thenAnswer(inv -> {
            StudentFees f = inv.getArgument(0);
            ReflectionTestUtils.setField(f, "id", 777L); // audit logging needs a real id, as a real save would assign
            return f;
        });

        StudentFeesCreateRequest request = new StudentFeesCreateRequest();
        request.setStudentId("NEW1");
        request.setClassName("6A");
        request.setYear("2025-2026");
        request.setMonth(6);
        request.setTakesBus(false);

        StudentFees created = service.createStudentFees(request);

        // The snapshot comes from the mocked computeMonthSnapshot stub (setUp default:
        // baseAmountDue=20.00), never from the request — the request has no such field.
        assertThat(created.getBaseAmountDue()).isEqualByComparingTo("20.00");
        assertThat(created.getPaid()).isFalse();
        assertThat(created.getAmountPaid()).isNull();
        assertThat(created.getManuallyPaid()).isFalse();
    }

    @Test
    void createStudentFees_persistsLineItemsFromTheSnapshotLinkedToTheNewRow() {
        school(4);
        when(studentFeesRepository.findByStudentIdAndSchoolIdAndYearAndMonth("NEW1", SCHOOL_ID, "2025-2026", 6))
                .thenReturn(null);
        when(feeCalculationService.validateFeeConfiguration(SCHOOL_ID, "2025-2026", "6A"))
                .thenReturn(FeeCalculationService.FeeConfigurationStatus.ok());
        when(studentFeesRepository.save(any(StudentFees.class))).thenAnswer(inv -> {
            StudentFees f = inv.getArgument(0);
            ReflectionTestUtils.setField(f, "id", 778L);
            return f;
        });
        when(feeCalculationService.computeMonthSnapshot(
                        any(), any(), any(), any(), anyInt(), anyBoolean(), any(), any(), any(), any()))
                .thenReturn(new FeeCalculationService.MonthSnapshot(
                        BigDecimal.valueOf(60000, 2), BigDecimal.ZERO, BigDecimal.ZERO, "{}", List.of(),
                        SnapshotStatus.COMPUTED,
                        List.of(new FeeCalculationService.LineItemSnapshot(
                                "FEE_HEAD", 22L, "LAB", "Laboratory Fee", "MONTHLY", 60000L, 0L, null))));

        StudentFeesCreateRequest request = new StudentFeesCreateRequest();
        request.setStudentId("NEW1");
        request.setClassName("6A");
        request.setYear("2025-2026");
        request.setMonth(6);
        request.setTakesBus(false);

        service.createStudentFees(request);

        ArgumentCaptor<StudentFeesLineItem> liCaptor = ArgumentCaptor.forClass(StudentFeesLineItem.class);
        verify(studentFeesLineItemRepository).save(liCaptor.capture());
        assertThat(liCaptor.getValue().getStudentFeesId()).isEqualTo(778L);
        assertThat(liCaptor.getValue().getFeeHeadName()).isEqualTo("Laboratory Fee");
        assertThat(liCaptor.getValue().getNetAmountPaise()).isEqualTo(60000L);
    }

    @Test
    void createStudentFees_rowAlreadyExists_rejectedRatherThanDuplicating() {
        existingRow(6, BigDecimal.valueOf(2000), BigDecimal.ZERO, BigDecimal.ZERO);
        StudentFeesCreateRequest request = new StudentFeesCreateRequest();
        request.setStudentId("S1");
        request.setClassName("6A");
        request.setYear("2025-2026");
        request.setMonth(6);

        assertThatThrownBy(() -> service.createStudentFees(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");

        verify(studentFeesRepository, never()).save(any());
    }

    // ─── getStudentFees: payment provenance (fee UI regression fix) ────────────
    // Fee UI regression fix: the month-card badge used to hardcode "Manual" for any
    // manuallyPaid row, losing the actual mode (CASH/CHEQUE/UPI/...) captured by
    // recordManualPayment, and never distinguished a Razorpay-funded row by name either.
    // These tests lock in that getStudentFees now derives the true funding source per row
    // from the allocation ledger (payment_student_fees_allocation joined to payment).

    private PaymentStudentFeesAllocation allocation(long id, long paymentId, long studentFeesId, long amountPaise) {
        PaymentStudentFeesAllocation a = new PaymentStudentFeesAllocation();
        ReflectionTestUtils.setField(a, "id", id);
        a.setPaymentId(paymentId);
        a.setStudentFeesId(studentFeesId);
        a.setSchoolId(SCHOOL_ID);
        a.setStudentId("S1");
        a.setSession("2025-2026");
        a.setMonth(1);
        a.setAmountPaise(amountPaise);
        return a;
    }

    private Payment manualPayment(long id, String mode) {
        Payment p = new Payment();
        ReflectionTestUtils.setField(p, "id", id);
        p.setStudentId("S1");
        p.setManualPaymentMode(mode);
        return p;
    }

    private Payment gatewayPayment(long id) {
        Payment p = new Payment();
        ReflectionTestUtils.setField(p, "id", id);
        p.setStudentId("S1");
        p.setManualPaymentMode(null);
        return p;
    }

    @Test
    void getStudentFees_manualCashPayment_provenanceIsCash() {
        StudentFees row = existingRow(1, BigDecimal.valueOf(5000), BigDecimal.valueOf(800), BigDecimal.ZERO);
        when(studentFeesRepository.findByStudentIdAndSchoolIdAndYearOrderByMonthAsc("S1", SCHOOL_ID, "2025-2026"))
                .thenReturn(List.of(row));
        PaymentStudentFeesAllocation alloc = allocation(1L, 501L, row.getId(), 580000L);
        when(paymentAllocationRepository.findByStudentFeesId(row.getId())).thenReturn(List.of(alloc));
        when(allocationRefundRepository.sumAmountPaiseByAllocationId(1L)).thenReturn(0L);
        when(paymentRepository.findById(501L)).thenReturn(Optional.of(manualPayment(501L, "CASH")));

        List<StudentFees> result = service.getStudentFees("S1", "2025-2026");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPaymentProvenance()).isEqualTo("CASH");
    }

    @Test
    void getStudentFees_manualChequePayment_provenanceIsCheque() {
        StudentFees row = existingRow(1, BigDecimal.valueOf(5000), BigDecimal.valueOf(800), BigDecimal.ZERO);
        when(studentFeesRepository.findByStudentIdAndSchoolIdAndYearOrderByMonthAsc("S1", SCHOOL_ID, "2025-2026"))
                .thenReturn(List.of(row));
        PaymentStudentFeesAllocation alloc = allocation(2L, 502L, row.getId(), 580000L);
        when(paymentAllocationRepository.findByStudentFeesId(row.getId())).thenReturn(List.of(alloc));
        when(allocationRefundRepository.sumAmountPaiseByAllocationId(2L)).thenReturn(0L);
        when(paymentRepository.findById(502L)).thenReturn(Optional.of(manualPayment(502L, "CHEQUE")));

        List<StudentFees> result = service.getStudentFees("S1", "2025-2026");

        assertThat(result.get(0).getPaymentProvenance()).isEqualTo("CHEQUE");
    }

    @Test
    void getStudentFees_manualUpiPayment_provenanceIsUpi() {
        StudentFees row = existingRow(1, BigDecimal.valueOf(5000), BigDecimal.valueOf(800), BigDecimal.ZERO);
        when(studentFeesRepository.findByStudentIdAndSchoolIdAndYearOrderByMonthAsc("S1", SCHOOL_ID, "2025-2026"))
                .thenReturn(List.of(row));
        PaymentStudentFeesAllocation alloc = allocation(3L, 503L, row.getId(), 580000L);
        when(paymentAllocationRepository.findByStudentFeesId(row.getId())).thenReturn(List.of(alloc));
        when(allocationRefundRepository.sumAmountPaiseByAllocationId(3L)).thenReturn(0L);
        when(paymentRepository.findById(503L)).thenReturn(Optional.of(manualPayment(503L, "UPI")));

        List<StudentFees> result = service.getStudentFees("S1", "2025-2026");

        assertThat(result.get(0).getPaymentProvenance()).isEqualTo("UPI");
    }

    @Test
    void getStudentFees_razorpayFundedMonth_provenanceIsRazorpayNotManual() {
        StudentFees row = existingRow(1, BigDecimal.valueOf(5000), BigDecimal.valueOf(800), BigDecimal.ZERO);
        when(studentFeesRepository.findByStudentIdAndSchoolIdAndYearOrderByMonthAsc("S1", SCHOOL_ID, "2025-2026"))
                .thenReturn(List.of(row));
        PaymentStudentFeesAllocation alloc = allocation(4L, 504L, row.getId(), 580000L);
        when(paymentAllocationRepository.findByStudentFeesId(row.getId())).thenReturn(List.of(alloc));
        when(allocationRefundRepository.sumAmountPaiseByAllocationId(4L)).thenReturn(0L);
        when(paymentRepository.findById(504L)).thenReturn(Optional.of(gatewayPayment(504L)));

        List<StudentFees> result = service.getStudentFees("S1", "2025-2026");

        assertThat(result.get(0).getPaymentProvenance()).isEqualTo("RAZORPAY");
        assertThat(result.get(0).getPaymentProvenance()).isNotEqualTo("MANUAL");
    }

    @Test
    void getStudentFees_mixedFunding_representedAsMixedNotArbitraryLastWriter() {
        StudentFees row = existingRow(1, BigDecimal.valueOf(5000), BigDecimal.valueOf(800), BigDecimal.ZERO);
        when(studentFeesRepository.findByStudentIdAndSchoolIdAndYearOrderByMonthAsc("S1", SCHOOL_ID, "2025-2026"))
                .thenReturn(List.of(row));
        // Two payments both still net-contributing to the same row: one manual (CASH), one
        // gateway — genuinely mixed funding, not a single source.
        PaymentStudentFeesAllocation cashAlloc = allocation(5L, 505L, row.getId(), 300000L);
        PaymentStudentFeesAllocation razorpayAlloc = allocation(6L, 506L, row.getId(), 280000L);
        when(paymentAllocationRepository.findByStudentFeesId(row.getId())).thenReturn(List.of(cashAlloc, razorpayAlloc));
        when(allocationRefundRepository.sumAmountPaiseByAllocationId(5L)).thenReturn(0L);
        when(allocationRefundRepository.sumAmountPaiseByAllocationId(6L)).thenReturn(0L);
        when(paymentRepository.findById(505L)).thenReturn(Optional.of(manualPayment(505L, "CASH")));
        when(paymentRepository.findById(506L)).thenReturn(Optional.of(gatewayPayment(506L)));

        List<StudentFees> result = service.getStudentFees("S1", "2025-2026");

        assertThat(result.get(0).getPaymentProvenance()).isEqualTo("MIXED");
    }

    @Test
    void getStudentFees_fullyRefundedAllocation_noLongerCountsAsFundingSource() {
        StudentFees row = existingRow(1, BigDecimal.valueOf(5000), BigDecimal.valueOf(800), BigDecimal.ZERO);
        when(studentFeesRepository.findByStudentIdAndSchoolIdAndYearOrderByMonthAsc("S1", SCHOOL_ID, "2025-2026"))
                .thenReturn(List.of(row));
        PaymentStudentFeesAllocation alloc = allocation(7L, 507L, row.getId(), 580000L);
        when(paymentAllocationRepository.findByStudentFeesId(row.getId())).thenReturn(List.of(alloc));
        // Fully reversed — this allocation no longer contributes net money to the row.
        when(allocationRefundRepository.sumAmountPaiseByAllocationId(7L)).thenReturn(580000L);

        List<StudentFees> result = service.getStudentFees("S1", "2025-2026");

        assertThat(result.get(0).getPaymentProvenance()).isNull();
        verify(paymentRepository, never()).findById(507L);
    }

    @Test
    void getStudentFees_unpaidMonth_provenanceIsNull() {
        StudentFees row = existingRow(1, BigDecimal.valueOf(5000), BigDecimal.valueOf(800), BigDecimal.ZERO);
        when(studentFeesRepository.findByStudentIdAndSchoolIdAndYearOrderByMonthAsc("S1", SCHOOL_ID, "2025-2026"))
                .thenReturn(List.of(row));
        when(paymentAllocationRepository.findByStudentFeesId(row.getId())).thenReturn(List.of());

        List<StudentFees> result = service.getStudentFees("S1", "2025-2026");

        assertThat(result.get(0).getPaymentProvenance()).isNull();
    }
}
