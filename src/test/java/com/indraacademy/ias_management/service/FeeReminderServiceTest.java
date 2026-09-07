package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.CheckoutQuoteDto;
import com.indraacademy.ias_management.dto.OverdueStudentDto;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.SnapshotStatus;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentFees;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.repository.AcademicSessionRepository;
import com.indraacademy.ias_management.repository.PaymentRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentFeesRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Consumer-migration coverage: FeeReminderService.getOverdueStudents' totalDue now delegates
 * to StudentFeesService.computeCheckoutQuote — the exact same backend-authoritative calculation
 * the Fees UI's checkout screen uses — instead of separately re-summing each month's snapshot
 * via FeeCalculationService.resolveSchoolFeeDue. That prior approach silently omitted the late
 * fee accrued on overdue months, understating what a parent actually owes vs. what the Fees page
 * shows for the identical months (confirmed live: ₹17,800 reported vs. ₹18,160 actually owed
 * once the accrued late fee is included). totalDue = schoolFeeDue + lateFee, deliberately
 * EXCLUDING platformFee — per CheckoutQuoteDto's own contract that's a payment-time-only
 * convenience charge, never part of the underlying school debt. If any month in the quote is
 * unresolved, the WHOLE student's totalDue becomes null (never a partial/understated sum) — the
 * existing "null means unknown, not ₹0" convention this method already documented.
 */
@ExtendWith(MockitoExtension.class)
class FeeReminderServiceTest {

    @Mock private StudentFeesRepository studentFeesRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private SchoolRepository schoolRepository;
    @Mock private FeeCalculationService feeCalculationService;
    @Mock private StudentFeesService studentFeesService;
    @Mock private PaymentRepository paymentRepository;
    @Mock private SecurityUtil securityUtil;
    @Mock private AuditService auditService;
    @Mock private BusinessNotificationService businessNotifications;
    @Mock private HttpServletRequest request;
    @Mock private AcademicSessionRepository academicSessionRepository;

    private FeeReminderService service;

    private static final Long SCHOOL_ID = 1L;
    private static final String SESSION = "2025-2026";

    @BeforeEach
    void setUp() {
        service = new FeeReminderService();
        ReflectionTestUtils.setField(service, "studentFeesRepository", studentFeesRepository);
        ReflectionTestUtils.setField(service, "studentRepository", studentRepository);
        ReflectionTestUtils.setField(service, "schoolRepository", schoolRepository);
        ReflectionTestUtils.setField(service, "feeCalculationService", feeCalculationService);
        ReflectionTestUtils.setField(service, "studentFeesService", studentFeesService);
        ReflectionTestUtils.setField(service, "paymentRepository", paymentRepository);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        ReflectionTestUtils.setField(service, "businessNotifications", businessNotifications);
        ReflectionTestUtils.setField(service, "academicSessionRepository", academicSessionRepository);
        // Real instance, not a mock: academicMonthToDate/academicMonthForDate are pure date
        // computations with no dependency on this service's own @Autowired fields.
        ReflectionTestUtils.setField(service, "academicSessionService", new AcademicSessionService());

        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        School school = new School();
        school.setId(SCHOOL_ID);
        school.setAcademicYearStartMonth(4);
        lenient().when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school));
        lenient().when(paymentRepository.findLatestPaymentDateByStudentIdAndSchoolIdAndSession(any(), any(), any()))
                .thenReturn(Optional.empty());
        // Real, resolved AcademicSession boundaries — getOverdueStudents/buildReminderEmailContent
        // now derive each month's date from this row's own startDate rather than a parsed label +
        // the school's global academicYearStartMonth.
        AcademicSession session = new AcademicSession();
        session.setSchoolId(SCHOOL_ID);
        session.setLabel(SESSION);
        session.setStartDate(java.time.LocalDate.of(2025, 4, 1));
        session.setEndDate(java.time.LocalDate.of(2026, 3, 31));
        lenient().when(academicSessionRepository.findBySchoolIdAndLabel(SCHOOL_ID, SESSION)).thenReturn(Optional.of(session));
    }

    private Student activeStudent(String studentId, String className) {
        Student s = new Student();
        s.setStudentId(studentId);
        s.setClassName(className);
        s.setStatus(StudentStatus.ACTIVE);
        s.setName("Test Student");
        return s;
    }

    private Student studentWithStatus(String studentId, StudentStatus status) {
        Student s = new Student();
        s.setStudentId(studentId);
        s.setClassName("5A");
        s.setStatus(status);
        s.setName("Test Student");
        s.setEmail("parent@example.com");
        return s;
    }

    private StudentFees unpaidRow(String studentId, int month, BigDecimal baseAmountDue, BigDecimal busFeeDue,
                                   BigDecimal discountAmount, SnapshotStatus status) {
        StudentFees fee = new StudentFees();
        fee.setStudentId(studentId);
        fee.setSchoolId(SCHOOL_ID);
        fee.setYear(SESSION);
        fee.setMonth(month);
        fee.setPaid(false);
        fee.setBaseAmountDue(baseAmountDue);
        fee.setBusFeeDue(busFeeDue);
        fee.setDiscountAmount(discountAmount);
        fee.setSnapshotStatus(status);
        return fee;
    }

    /** Mirrors StudentFeesService.computeCheckoutQuote's own output shape. */
    private CheckoutQuoteDto quote(String studentId, List<Integer> months, BigDecimal schoolFeeDue,
                                    BigDecimal lateFee, BigDecimal platformFee, List<Integer> unresolvedMonths) {
        CheckoutQuoteDto dto = new CheckoutQuoteDto();
        dto.setStudentId(studentId);
        dto.setSession(SESSION);
        dto.setMonths(months);
        dto.setSchoolFeeDue(schoolFeeDue);
        dto.setLateFee(lateFee);
        dto.setPlatformFee(platformFee);
        dto.setTotalAmount(schoolFeeDue.add(lateFee).add(platformFee));
        dto.setUnresolvedMonths(unresolvedMonths);
        return dto;
    }

    @Test
    void totalDue_delegatesToCheckoutQuote_forTheStudentsFullUnpaidOverdueMonthList() {
        StudentFees month1 = unpaidRow("S1", 1, BigDecimal.valueOf(2000), BigDecimal.ZERO, BigDecimal.ZERO, SnapshotStatus.COMPUTED);
        StudentFees month2 = unpaidRow("S1", 2, BigDecimal.valueOf(2000), BigDecimal.valueOf(800), BigDecimal.valueOf(200), SnapshotStatus.COMPUTED);
        when(studentFeesRepository.findAllUnpaidBySchoolIdAndSession(SCHOOL_ID, SESSION)).thenReturn(List.of(month1, month2));
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(Optional.of(activeStudent("S1", "5A")));
        when(studentFeesService.computeCheckoutQuote("S1", SESSION, List.of(1, 2)))
                .thenReturn(quote("S1", List.of(1, 2), BigDecimal.valueOf(4600), BigDecimal.ZERO, BigDecimal.ZERO, List.of()));

        List<OverdueStudentDto> result = service.getOverdueStudents(SESSION, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTotalDue()).isEqualTo(4600.0);
    }

    @Test
    void totalDue_includesAccruedLateFee_matchingWhatTheFeesUiCheckoutQuoteShows() {
        // The actual bug this covers: totalDue used to sum only each row's resolved snapshot
        // (schoolFeeDue), silently dropping the late fee that accrues on an overdue month —
        // reported ₹17,800 here while the Fees UI's checkout quote for the identical month
        // showed ₹18,160 (schoolFeeDue 17800 + lateFee 360). totalDue must include lateFee.
        StudentFees overdueMonth = unpaidRow("S1", 1, BigDecimal.valueOf(17000), BigDecimal.valueOf(800), BigDecimal.ZERO, SnapshotStatus.COMPUTED);
        when(studentFeesRepository.findAllUnpaidBySchoolIdAndSession(SCHOOL_ID, SESSION)).thenReturn(List.of(overdueMonth));
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(Optional.of(activeStudent("S1", "5A")));
        when(studentFeesService.computeCheckoutQuote("S1", SESSION, List.of(1)))
                .thenReturn(quote("S1", List.of(1), BigDecimal.valueOf(17800), BigDecimal.valueOf(360), BigDecimal.valueOf(273), List.of()));

        List<OverdueStudentDto> result = service.getOverdueStudents(SESSION, null);

        assertThat(result.get(0).getTotalDue()).isEqualTo(18160.0); // 17800 schoolFeeDue + 360 lateFee
    }

    @Test
    void totalDue_excludesPlatformFee_notPartOfTheUnderlyingSchoolDebt() {
        StudentFees overdueMonth = unpaidRow("S1", 1, BigDecimal.valueOf(17000), BigDecimal.valueOf(800), BigDecimal.ZERO, SnapshotStatus.COMPUTED);
        when(studentFeesRepository.findAllUnpaidBySchoolIdAndSession(SCHOOL_ID, SESSION)).thenReturn(List.of(overdueMonth));
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(Optional.of(activeStudent("S1", "5A")));
        when(studentFeesService.computeCheckoutQuote("S1", SESSION, List.of(1)))
                .thenReturn(quote("S1", List.of(1), BigDecimal.valueOf(17800), BigDecimal.valueOf(360), BigDecimal.valueOf(273), List.of()));

        List<OverdueStudentDto> result = service.getOverdueStudents(SESSION, null);

        // 18433 would be totalAmount (schoolFeeDue+lateFee+platformFee) — the payment-time
        // total, not the debt total. totalDue must stop at 18160, never reach 18433.
        assertThat(result.get(0).getTotalDue()).isNotEqualTo(18433.0);
        assertThat(result.get(0).getTotalDue()).isEqualTo(18160.0);
    }

    @Test
    void totalDue_isNullWhenAnySingleMonthIsUnresolvable_neverAPartialSum() {
        StudentFees resolvable = unpaidRow("S1", 1, BigDecimal.valueOf(2000), BigDecimal.ZERO, BigDecimal.ZERO, SnapshotStatus.COMPUTED);
        StudentFees unresolvable = unpaidRow("S1", 2, null, null, null, null); // legacy row, no live rules either
        when(studentFeesRepository.findAllUnpaidBySchoolIdAndSession(SCHOOL_ID, SESSION)).thenReturn(List.of(resolvable, unresolvable));
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(Optional.of(activeStudent("S1", "5A")));
        when(studentFeesService.computeCheckoutQuote("S1", SESSION, List.of(1, 2)))
                .thenReturn(quote("S1", List.of(1, 2), BigDecimal.valueOf(2000), BigDecimal.ZERO, BigDecimal.ZERO, List.of(2)));

        List<OverdueStudentDto> result = service.getOverdueStudents(SESSION, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTotalDue()).isNull(); // NOT 2000.0 — a partial sum would understate what's owed
    }

    @Test
    void totalDue_confidentZeroFromSnapshotIsReportedAsZeroNotNull() {
        // A waiver/off-schedule month resolves to a real, confident 0 — must be reported as
        // 0.0, distinct from the null-means-unknown case above.
        StudentFees waived = unpaidRow("S1", 1, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(2000), SnapshotStatus.COMPUTED);
        when(studentFeesRepository.findAllUnpaidBySchoolIdAndSession(SCHOOL_ID, SESSION)).thenReturn(List.of(waived));
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(Optional.of(activeStudent("S1", "5A")));
        when(studentFeesService.computeCheckoutQuote("S1", SESSION, List.of(1)))
                .thenReturn(quote("S1", List.of(1), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of()));

        List<OverdueStudentDto> result = service.getOverdueStudents(SESSION, null);

        assertThat(result.get(0).getTotalDue()).isEqualTo(0.0);
    }

    @Test
    void inactiveStudentsAreExcluded() {
        Student inactive = activeStudent("S2", "5A");
        inactive.setStatus(StudentStatus.INACTIVE);
        StudentFees fee = unpaidRow("S2", 1, BigDecimal.valueOf(2000), BigDecimal.ZERO, BigDecimal.ZERO, SnapshotStatus.COMPUTED);
        when(studentFeesRepository.findAllUnpaidBySchoolIdAndSession(SCHOOL_ID, SESSION)).thenReturn(List.of(fee));
        when(studentRepository.findByStudentIdAndSchoolId("S2", SCHOOL_ID)).thenReturn(Optional.of(inactive));

        List<OverdueStudentDto> result = service.getOverdueStudents(SESSION, null);

        assertThat(result).isEmpty();
    }

    // ─── Send-boundary status enforcement (defense in depth: not just candidate-list filtering) ───

    @Test
    void sendReminder_skipsTransferredStudent() {
        when(studentRepository.findByStudentIdAndSchoolId("S3", SCHOOL_ID))
                .thenReturn(Optional.of(studentWithStatus("S3", StudentStatus.TRANSFERRED)));

        FeeReminderService.ReminderOutcome outcome = service.sendReminder("S3", SESSION, request);

        assertThat(outcome).isEqualTo(FeeReminderService.ReminderOutcome.SKIPPED_NOT_ACTIVE);
        verify(auditService, never()).log(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void sendReminder_skipsWithdrawnStudent() {
        when(studentRepository.findByStudentIdAndSchoolId("S4", SCHOOL_ID))
                .thenReturn(Optional.of(studentWithStatus("S4", StudentStatus.WITHDRAWN)));

        FeeReminderService.ReminderOutcome outcome = service.sendReminder("S4", SESSION, request);

        assertThat(outcome).isEqualTo(FeeReminderService.ReminderOutcome.SKIPPED_NOT_ACTIVE);
    }

    @Test
    void sendReminder_skipsGraduatedStudent() {
        when(studentRepository.findByStudentIdAndSchoolId("S5", SCHOOL_ID))
                .thenReturn(Optional.of(studentWithStatus("S5", StudentStatus.GRADUATED)));

        FeeReminderService.ReminderOutcome outcome = service.sendReminder("S5", SESSION, request);

        assertThat(outcome).isEqualTo(FeeReminderService.ReminderOutcome.SKIPPED_NOT_ACTIVE);
    }

    @Test
    void sendReminder_skipsInactiveStudent() {
        when(studentRepository.findByStudentIdAndSchoolId("S6", SCHOOL_ID))
                .thenReturn(Optional.of(studentWithStatus("S6", StudentStatus.INACTIVE)));

        FeeReminderService.ReminderOutcome outcome = service.sendReminder("S6", SESSION, request);

        assertThat(outcome).isEqualTo(FeeReminderService.ReminderOutcome.SKIPPED_NOT_ACTIVE);
    }

    @Test
    void sendReminder_activeStudentStillReceivesReminder() {
        when(studentRepository.findByStudentIdAndSchoolId("S7", SCHOOL_ID))
                .thenReturn(Optional.of(studentWithStatus("S7", StudentStatus.ACTIVE)));
        when(studentFeesRepository.findAllUnpaidBySchoolIdAndSessionAndClassName(SCHOOL_ID, SESSION, "5A"))
                .thenReturn(List.of());

        FeeReminderService.ReminderOutcome outcome = service.sendReminder("S7", SESSION, request);

        assertThat(outcome).isEqualTo(FeeReminderService.ReminderOutcome.SENT);
        verify(businessNotifications).studentAndParents(anyLong(), anyString(), any(), any(), any(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anySet());
        verify(auditService).log(any(), any(), eq("SEND_FEE_REMINDER"), any(), eq("S7"), any(), any(), any());
    }

    @Test
    void sendBulkReminders_aStaleExitedStudentIdIsSkippedNotSilentlySent_activeStudentInSameBatchStillSent() {
        when(studentRepository.findByStudentIdAndSchoolId("EXITED", SCHOOL_ID))
                .thenReturn(Optional.of(studentWithStatus("EXITED", StudentStatus.WITHDRAWN)));
        when(studentRepository.findByStudentIdAndSchoolId("S7", SCHOOL_ID))
                .thenReturn(Optional.of(studentWithStatus("S7", StudentStatus.ACTIVE)));
        when(studentFeesRepository.findAllUnpaidBySchoolIdAndSessionAndClassName(SCHOOL_ID, SESSION, "5A"))
                .thenReturn(List.of());

        FeeReminderService.BulkReminderResult result =
                service.sendBulkReminders(List.of("EXITED", "S7"), SESSION, request);

        assertThat(result.sent()).isEqualTo(1);
        assertThat(result.skipped()).containsExactly(Map.of("studentId", "EXITED", "reason", "skipped_not_active"));
        // Exactly one notification — the exited student's stale ID must never reach the recipient resolver.
        verify(businessNotifications, times(1)).studentAndParents(anyLong(), anyString(), any(), any(), any(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anySet());
    }

    @Test
    void aiWorkflowDispatch_exitedStudentIsSkippedNotSilentlySent() {
        when(studentRepository.findByStudentIdAndSchoolId("EXITED", SCHOOL_ID))
                .thenReturn(Optional.of(studentWithStatus("EXITED", StudentStatus.GRADUATED)));

        Map<String, String> outcomes = service.sendReminderEmailsWithOutcomes(List.of("EXITED"), SESSION);

        assertThat(outcomes).containsEntry("EXITED", "skipped_not_active");
    }

    // ─── sendMonthlyFeeReminders: session authority (Fees/Payments audit phase) ────────────

    @Test
    void sendMonthlyFeeReminders_usesTheConfiguredCurrentSessionLabel_neverATodaysDateGuess() {
        School school = new School();
        school.setId(SCHOOL_ID);
        school.setActive(true);
        school.setAcademicYearStartMonth(4);
        when(schoolRepository.findAll()).thenReturn(List.of(school));

        // A label that could never be produced by "today's year" +/- 1 arithmetic — proving
        // the value used came from the DB's current-session row, not date math.
        AcademicSession currentSession = new AcademicSession();
        currentSession.setSchoolId(SCHOOL_ID);
        currentSession.setLabel("CUSTOM-SESSION-9000");
        currentSession.setStartDate(LocalDate.of(2000, 1, 1));
        currentSession.setEndDate(LocalDate.of(2099, 12, 31));
        when(academicSessionRepository.findBySchoolIdAndCurrentTrue(SCHOOL_ID)).thenReturn(Optional.of(currentSession));
        when(studentFeesRepository.findAllUnpaidBySchoolIdAndYearAndMonth(eq(SCHOOL_ID), eq("CUSTOM-SESSION-9000"), anyInt()))
                .thenReturn(List.of());

        service.sendMonthlyFeeReminders();

        verify(studentFeesRepository).findAllUnpaidBySchoolIdAndYearAndMonth(eq(SCHOOL_ID), eq("CUSTOM-SESSION-9000"), anyInt());
    }

    @Test
    void sendMonthlyFeeReminders_noCurrentSessionConfigured_skipsTheSchoolRatherThanGuessing() {
        School school = new School();
        school.setId(SCHOOL_ID);
        school.setActive(true);
        school.setAcademicYearStartMonth(4);
        when(schoolRepository.findAll()).thenReturn(List.of(school));
        when(academicSessionRepository.findBySchoolIdAndCurrentTrue(SCHOOL_ID)).thenReturn(Optional.empty());

        service.sendMonthlyFeeReminders();

        verify(studentFeesRepository, never()).findAllUnpaidBySchoolIdAndYearAndMonth(any(), any(), anyInt());
    }
}
