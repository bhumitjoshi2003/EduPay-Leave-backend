package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.FeeWorkflowDtos.TransportChangeRequest;
import com.indraacademy.ias_management.dto.FeeWorkflowDtos.BulkDiscountRequest;
import com.indraacademy.ias_management.dto.FeeWorkflowDtos.WorkflowChangeResult;
import com.indraacademy.ias_management.dto.FeeWorkflowDtos.AssignmentRequest;
import com.indraacademy.ias_management.dto.FeeWorkflowDtos.StudentPreview;
import com.indraacademy.ias_management.dto.FeeWorkflowDtos.GenerationResult;
import com.indraacademy.ias_management.dto.FeeWorkflowDtos.DiscountExpireRequest;
import com.indraacademy.ias_management.dto.FeeWorkflowDtos.RevokeFutureRequest;
import com.indraacademy.ias_management.dto.FeeWorkflowDtos.TransportCorrectionRequest;
import com.indraacademy.ias_management.dto.RecalculationEntryDto;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.FeeConfigType;
import com.indraacademy.ias_management.entity.FeeHead;
import com.indraacademy.ias_management.entity.StudentFeeConfig;
import com.indraacademy.ias_management.entity.StudentTransportFeeAssignment;
import com.indraacademy.ias_management.entity.SchoolFeeSettings;
import com.indraacademy.ias_management.entity.MidSessionFeePolicy;
import com.indraacademy.ias_management.entity.SnapshotStatus;
import com.indraacademy.ias_management.entity.FeeOperationalStatus;
import com.indraacademy.ias_management.entity.StudentFeeAssignment;
import com.indraacademy.ias_management.entity.StudentFeeAssignmentStatus;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentFees;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FeeWorkflowServiceTest {
    @Mock private SchoolFeeSettingsRepository settingsRepository;
    @Mock private StudentFeeAssignmentRepository assignmentRepository;
    @Mock private StudentTransportFeeAssignmentRepository transportRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private StudentFeesRepository studentFeesRepository;
    @Mock private StudentFeesLineItemRepository lineItemRepository;
    @Mock private StudentOneTimeFeeChargedRepository oneTimeRepository;
    @Mock private SchoolRepository schoolRepository;
    @Mock private AcademicSessionRepository academicSessionRepository;
    @Mock private FeeHeadRepository feeHeadRepository;
    @Mock private StudentFeeConfigRepository feeConfigRepository;
    @Mock private FeeCalculationService calculationService;
    @Mock private StudentFeesRecalculationService recalculationService;
    @Mock private AuditService auditService;
    @Mock private SecurityUtil securityUtil;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private TransactionStatus transactionStatus;

    private FeeWorkflowService service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        lenient().when(securityUtil.getSchoolId()).thenReturn(1L);
        lenient().when(securityUtil.getUsername()).thenReturn("admin1");
        lenient().when(securityUtil.getRole()).thenReturn("ADMIN");
        lenient().when(calculationService.parseSession("2026-2027")).thenReturn(new int[]{2026, 2027});
        lenient().when(calculationService.academicMonthStart(anyInt(), eq(2026), eq(2027), eq(4)))
                .thenAnswer(invocation -> LocalDate.of(2026, 4, 1).plusMonths(invocation.<Integer>getArgument(0) - 1L));
        School school = new School();
        school.setId(1L);
        school.setAcademicYearStartMonth(4);
        lenient().when(schoolRepository.findById(1L)).thenReturn(Optional.of(school));
        service = new FeeWorkflowService(settingsRepository, assignmentRepository, transportRepository,
                studentRepository, studentFeesRepository, lineItemRepository, oneTimeRepository,
                schoolRepository, academicSessionRepository, feeHeadRepository, feeConfigRepository,
                calculationService, recalculationService, auditService, securityUtil, transactionManager);
    }

    @Test
    void changeTransport_recalculatesEligibleMonthsAndReportsProtectedRows() {
        Student student = new Student();
        student.setStudentId("S1");
        when(studentRepository.findByStudentIdInAndSchoolId(List.of("S1"), 1L)).thenReturn(List.of(student));
        when(transportRepository.findBySchoolIdAndStudentIdAndAcademicSessionOrderByEffectiveFromDesc(1L, "S1", "2026-2027"))
                .thenReturn(List.of());
        StudentFees august = fee(5);
        StudentFees september = fee(6);
        when(studentFeesRepository.findByStudentIdAndSchoolIdAndYearOrderByMonthAsc("S1", 1L, "2026-2027"))
                .thenReturn(List.of(august, september));
        RecalculationEntryDto changed = result(5, true, null);
        RecalculationEntryDto protectedRow = result(6, false, "Row is marked paid.");
        when(recalculationService.recalculateOneWithTransport(eq("S1"), eq("2026-2027"), eq(5), eq(true), eq(8.0), anyString(), anyString()))
                .thenReturn(changed);
        when(recalculationService.recalculateOneWithTransport(eq("S1"), eq("2026-2027"), eq(6), eq(true), eq(8.0), anyString(), anyString()))
                .thenReturn(protectedRow);

        WorkflowChangeResult response = service.changeTransport(new TransportChangeRequest(
                List.of("S1"), "2026-2027", true, 8.0, LocalDate.of(2026, 8, 15), "Started bus"), "127.0.0.1");

        assertThat(response.savedStudents()).isEqualTo(1);
        assertThat(response.recalculatedMonths()).isEqualTo(1);
        assertThat(response.skippedMonths()).isEqualTo(1);
        assertThat(response.students().getFirst().months()).hasSize(2);
        verify(transportRepository).save(argThat(value -> value.isEnabled() && value.getDistance().equals(8.0)));
    }

    @Test
    void applyBulkDiscount_recalculatesExistingEligibleMonths() {
        Student student = student("S1");
        AcademicSession session = session();
        FeeHead feeHead = feeHead();
        when(studentRepository.findByStudentIdInAndSchoolId(List.of("S1"), 1L)).thenReturn(List.of(student));
        when(academicSessionRepository.findById(10L)).thenReturn(Optional.of(session));
        when(feeHeadRepository.findByIdAndSchoolId(20L, 1L)).thenReturn(Optional.of(feeHead));
        when(feeConfigRepository.existsOverlapping(1L, "S1", 10L, 20L,
                LocalDate.of(2026, 8, 1), null)).thenReturn(false);
        when(studentFeesRepository.findByStudentIdAndSchoolIdAndYearOrderByMonthAsc("S1", 1L, "2026-2027"))
                .thenReturn(List.of(fee(5)));
        when(recalculationService.recalculateOne(eq("S1"), eq("2026-2027"), eq(5), anyString(), anyString()))
                .thenReturn(result(5, true, null));

        WorkflowChangeResult response = service.applyBulkDiscount(new BulkDiscountRequest(
                List.of("S1"), 10L, 20L, FeeConfigType.DISCOUNT_PERCENT, BigDecimal.TEN,
                LocalDate.of(2026, 8, 1), null, "Scholarship"), "127.0.0.1");

        assertThat(response.savedStudents()).isEqualTo(1);
        assertThat(response.recalculatedMonths()).isEqualTo(1);
        verify(feeConfigRepository).save(argThat(value -> value.getStudentId().equals("S1")
                && value.getConfigType() == FeeConfigType.DISCOUNT_PERCENT));
    }

    @Test
    void applyBulkDiscount_overlapForOneStudent_doesNotBlockOtherStudent() {
        Student first = student("S1");
        Student second = student("S2");
        AcademicSession session = session();
        FeeHead feeHead = feeHead();
        when(studentRepository.findByStudentIdInAndSchoolId(List.of("S1", "S2"), 1L)).thenReturn(List.of(first, second));
        when(academicSessionRepository.findById(10L)).thenReturn(Optional.of(session));
        when(feeHeadRepository.findByIdAndSchoolId(20L, 1L)).thenReturn(Optional.of(feeHead));
        when(feeConfigRepository.existsOverlapping(eq(1L), eq("S1"), eq(10L), eq(20L), any(), isNull())).thenReturn(true);
        when(feeConfigRepository.existsOverlapping(eq(1L), eq("S2"), eq(10L), eq(20L), any(), isNull())).thenReturn(false);
        when(studentFeesRepository.findByStudentIdAndSchoolIdAndYearOrderByMonthAsc("S2", 1L, "2026-2027"))
                .thenReturn(List.of());

        WorkflowChangeResult response = service.applyBulkDiscount(new BulkDiscountRequest(
                List.of("S1", "S2"), 10L, 20L, FeeConfigType.WAIVER, null,
                LocalDate.of(2026, 8, 1), null, "Scholarship"), "127.0.0.1");

        assertThat(response.savedStudents()).isEqualTo(1);
        assertThat(response.students()).extracting(value -> value.changeSaved()).containsExactly(false, true);
        verify(feeConfigRepository, times(1)).save(any(StudentFeeConfig.class));
    }

    @Test
    void changeTransport_crossSchoolStudent_isRejectedBeforeAnyWrite() {
        when(studentRepository.findByStudentIdInAndSchoolId(List.of("S1", "OTHER"), 1L))
                .thenReturn(List.of(student("S1")));

        assertThatThrownBy(() -> service.changeTransport(new TransportChangeRequest(
                List.of("S1", "OTHER"), "2026-2027", false, null,
                LocalDate.of(2026, 8, 1), "Stopped bus"), "127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found in this school");
        verifyNoInteractions(transportRepository);
    }

    @Test
    void revokeFutureDiscount_preservesRecordAndAddsAuditMetadata() {
        StudentFeeConfig config = discountConfig(LocalDate.now().plusMonths(2).withDayOfMonth(1));
        when(feeConfigRepository.findById(31L)).thenReturn(Optional.of(config));

        service.revokeFutureDiscount(31L, new RevokeFutureRequest("Award entered in error"), "127.0.0.1");

        assertThat(config.getRevokedAt()).isNotNull();
        assertThat(config.getRevokedBy()).isEqualTo("admin1");
        assertThat(config.getRevokeReason()).isEqualTo("Award entered in error");
        verify(feeConfigRepository).save(config);
        verify(auditService).log(eq("admin1"), eq("ADMIN"), eq("REVOKE_FUTURE_STUDENT_DISCOUNT"),
                eq("StudentFeeConfig"), eq("31"), isNull(), anyString(), eq("127.0.0.1"));
    }

    @Test
    void revokeActiveDiscount_isRejectedWithoutChangingHistory() {
        StudentFeeConfig config = discountConfig(LocalDate.now().minusMonths(1).withDayOfMonth(1));
        when(feeConfigRepository.findById(31L)).thenReturn(Optional.of(config));

        assertThatThrownBy(() -> service.revokeFutureDiscount(31L,
                new RevokeFutureRequest("Wrong award"), "127.0.0.1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("future discount");

        verify(feeConfigRepository, never()).save(any());
    }

    @Test
    void expireDiscount_closesRuleAtPriorMonthEnd() {
        StudentFeeConfig config = discountConfig(LocalDate.of(2026, 8, 1));
        when(feeConfigRepository.findById(31L)).thenReturn(Optional.of(config));
        when(studentFeesRepository.findByStudentIdAndSchoolIdAndYearOrderByMonthAsc("S1", 1L, "2026-2027"))
                .thenReturn(List.of());

        WorkflowChangeResult result = service.expireDiscount(31L,
                new DiscountExpireRequest(LocalDate.of(2026, 10, 18), "Scholarship ended"), "127.0.0.1");

        assertThat(config.getValidUntil()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(result.savedStudents()).isEqualTo(1);
        verify(feeConfigRepository).save(config);
    }

    @Test
    void correctFutureTransport_updatesOnlyFutureEntry() {
        StudentTransportFeeAssignment assignment = new StudentTransportFeeAssignment();
        assignment.setId(41L); assignment.setSchoolId(1L); assignment.setStudentId("S1");
        assignment.setAcademicSession("2026-2027"); assignment.setEffectiveFrom(LocalDate.now().plusMonths(1));
        assignment.setEnabled(true); assignment.setDistance(5.0);
        when(transportRepository.findByIdAndSchoolId(41L, 1L)).thenReturn(Optional.of(assignment));

        service.correctFutureTransport(41L, new TransportCorrectionRequest(true, 8.5, "Route corrected"), "127.0.0.1");

        assertThat(assignment.getDistance()).isEqualTo(8.5);
        assertThat(assignment.getChangedBy()).isEqualTo("admin1");
        verify(transportRepository).save(assignment);
    }

    @Test
    void preview_prorationPolicy_skipsEarlierMonthAndProratesEffectiveMonth() {
        Student student = student("S1");
        student.setName("Student One");
        student.setClassName("6A");
        student.setJoiningDate(LocalDate.of(2026, 8, 16));
        when(studentRepository.findByStudentIdInAndSchoolId(List.of("S1"), 1L)).thenReturn(List.of(student));
        SchoolFeeSettings settings = settings(MidSessionFeePolicy.PRORATE_JOINING_MONTH);
        settings.setActivationDate(LocalDate.of(2026, 8, 10));
        when(settingsRepository.findBySchoolId(1L)).thenReturn(Optional.of(settings));
        when(calculationService.validateFeeConfiguration(1L, "2026-2027", "6A"))
                .thenReturn(FeeCalculationService.FeeConfigurationStatus.ok());
        when(oneTimeRepository.findFeeHeadIdBySchoolIdAndStudentId(1L, "S1")).thenReturn(Set.of());
        when(transportRepository.effectiveOn(eq(1L), eq("S1"), eq("2026-2027"), any())).thenReturn(Optional.empty());
        FeeCalculationService.MonthSnapshot full = snapshot("1000.00");
        FeeCalculationService.MonthSnapshot prorated = snapshot("516.13");
        when(calculationService.computeMonthSnapshot(eq(1L), eq("2026-2027"), eq("6A"), eq("S1"),
                eq(5), eq(true), any(), eq(false), anyDouble(), any())).thenReturn(full);
        when(calculationService.prorateRecurringSnapshot(full, LocalDate.of(2026, 8, 16))).thenReturn(prorated);
        when(calculationService.prorationFactor(LocalDate.of(2026, 8, 16))).thenReturn(new BigDecimal("0.51612903"));

        List<StudentPreview> preview = service.preview(new AssignmentRequest(List.of("S1"), "2026-2027",
                LocalDate.of(2026, 8, 1), List.of(4, 5), null));

        assertThat(preview.getFirst().months().get(0).eligible()).isFalse();
        assertThat(preview.getFirst().months().get(0).message()).contains("Before");
        assertThat(preview.getFirst().months().get(1).eligible()).isTrue();
        assertThat(preview.getFirst().months().get(1).totalAmount()).isEqualByComparingTo("516.13");
        assertThat(preview.getFirst().months().get(1).prorationFactor()).isEqualByComparingTo("0.51612903");
    }

    @Test
    void preview_nextMonthPolicy_startsAfterEffectiveCalendarMonth() {
        Student student = student("S1");
        student.setName("Student One");
        student.setClassName("6A");
        student.setJoiningDate(LocalDate.of(2026, 8, 16));
        when(studentRepository.findByStudentIdInAndSchoolId(List.of("S1"), 1L)).thenReturn(List.of(student));
        when(settingsRepository.findBySchoolId(1L)).thenReturn(Optional.of(settings(MidSessionFeePolicy.NEXT_MONTH)));
        when(calculationService.validateFeeConfiguration(1L, "2026-2027", "6A"))
                .thenReturn(FeeCalculationService.FeeConfigurationStatus.ok());
        when(oneTimeRepository.findFeeHeadIdBySchoolIdAndStudentId(1L, "S1")).thenReturn(Set.of());
        when(transportRepository.effectiveOn(eq(1L), eq("S1"), eq("2026-2027"), any())).thenReturn(Optional.empty());
        when(calculationService.computeMonthSnapshot(eq(1L), eq("2026-2027"), eq("6A"), eq("S1"),
                eq(6), eq(true), any(), eq(false), anyDouble(), any())).thenReturn(snapshot("1000.00"));

        List<StudentPreview> preview = service.preview(new AssignmentRequest(List.of("S1"), "2026-2027",
                LocalDate.of(2026, 8, 1), List.of(5, 6), null));

        assertThat(preview.getFirst().months().get(0).eligible()).isFalse();
        assertThat(preview.getFirst().months().get(0).message()).contains("next-month");
        assertThat(preview.getFirst().months().get(1).eligible()).isTrue();
    }

    @Test
    void generate_prorationPolicy_persistsEffectiveDatePolicyAndFactor() {
        Student student = student("S1");
        student.setName("Student One");
        student.setClassName("6A");
        student.setJoiningDate(LocalDate.of(2026, 8, 16));
        when(studentRepository.findByStudentIdInAndSchoolId(List.of("S1"), 1L)).thenReturn(List.of(student));
        SchoolFeeSettings settings = settings(MidSessionFeePolicy.PRORATE_JOINING_MONTH);
        settings.setOperationalStatus(FeeOperationalStatus.ACTIVE);
        settings.setActivationDate(LocalDate.of(2026, 8, 1));
        when(settingsRepository.findBySchoolId(1L)).thenReturn(Optional.of(settings));
        StudentFeeAssignment assignment = new StudentFeeAssignment();
        assignment.setSchoolId(1L); assignment.setStudentId("S1"); assignment.setAcademicSession("2026-2027");
        assignment.setStatus(StudentFeeAssignmentStatus.READY);
        when(assignmentRepository.findBySchoolIdAndStudentIdAndAcademicSession(1L, "S1", "2026-2027"))
                .thenReturn(Optional.of(assignment));
        when(calculationService.validateFeeConfiguration(1L, "2026-2027", "6A"))
                .thenReturn(FeeCalculationService.FeeConfigurationStatus.ok());
        when(oneTimeRepository.findFeeHeadIdBySchoolIdAndStudentId(1L, "S1")).thenReturn(Set.of());
        when(studentFeesRepository.findByStudentIdAndSchoolIdAndYearOrderByMonthAsc("S1", 1L, "2026-2027"))
                .thenReturn(List.of());
        when(studentFeesRepository.findByStudentIdAndSchoolIdAndYearAndMonth("S1", 1L, "2026-2027", 5))
                .thenReturn(null);
        when(transportRepository.effectiveOn(eq(1L), eq("S1"), eq("2026-2027"), any())).thenReturn(Optional.empty());
        FeeCalculationService.MonthSnapshot full = snapshot("1000.00");
        FeeCalculationService.MonthSnapshot prorated = snapshot("516.13");
        when(calculationService.computeMonthSnapshot(eq(1L), eq("2026-2027"), eq("6A"), eq("S1"),
                eq(5), eq(true), any(), eq(false), anyDouble(), any())).thenReturn(full);
        when(calculationService.prorateRecurringSnapshot(full, LocalDate.of(2026, 8, 16))).thenReturn(prorated);
        when(calculationService.prorationFactor(LocalDate.of(2026, 8, 16))).thenReturn(new BigDecimal("0.51612903"));

        List<GenerationResult> results = service.generate(new AssignmentRequest(List.of("S1"), "2026-2027",
                LocalDate.of(2026, 8, 1), List.of(5), null), "127.0.0.1");

        assertThat(results.getFirst().successful()).isTrue();
        verify(studentFeesRepository).save(argThat(value -> value.getBillingEffectiveDate().equals(LocalDate.of(2026, 8, 16))
                && value.getMidSessionFeePolicy() == MidSessionFeePolicy.PRORATE_JOINING_MONTH
                && value.getProrationFactor().compareTo(new BigDecimal("0.51612903")) == 0
                && value.getBaseAmountDue().compareTo(new BigDecimal("516.13")) == 0));
    }

    private SchoolFeeSettings settings(MidSessionFeePolicy policy) {
        SchoolFeeSettings value = new SchoolFeeSettings();
        value.setSchoolId(1L);
        value.setMidSessionPolicy(policy);
        return value;
    }

    private FeeCalculationService.MonthSnapshot snapshot(String baseAmount) {
        return new FeeCalculationService.MonthSnapshot(new BigDecimal(baseAmount), BigDecimal.ZERO,
                BigDecimal.ZERO, "{}", List.of(), SnapshotStatus.COMPUTED, List.of());
    }

    private Student student(String id) {
        Student value = new Student();
        value.setStudentId(id);
        return value;
    }

    private AcademicSession session() {
        AcademicSession value = new AcademicSession();
        value.setId(10L);
        value.setSchoolId(1L);
        value.setLabel("2026-2027");
        return value;
    }

    private FeeHead feeHead() {
        FeeHead value = new FeeHead();
        value.setId(20L);
        return value;
    }

    private StudentFeeConfig discountConfig(LocalDate validFrom) {
        StudentFeeConfig value = new StudentFeeConfig();
        value.setId(31L); value.setSchoolId(1L); value.setStudentId("S1");
        value.setAcademicSession(session()); value.setFeeHead(feeHead());
        value.setConfigType(FeeConfigType.DISCOUNT_PERCENT); value.setValue(BigDecimal.TEN);
        value.setValidFrom(validFrom); value.setReason("Scholarship");
        return value;
    }

    private StudentFees fee(int month) {
        StudentFees fee = new StudentFees();
        fee.setMonth(month);
        return fee;
    }

    private RecalculationEntryDto result(int month, boolean ok, String message) {
        RecalculationEntryDto value = new RecalculationEntryDto();
        value.setMonth(month);
        value.setOk(ok);
        value.setMessage(message);
        return value;
    }
}
