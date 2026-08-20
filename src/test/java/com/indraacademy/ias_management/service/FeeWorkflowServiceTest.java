package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.FeeWorkflowDtos.TransportChangeRequest;
import com.indraacademy.ias_management.dto.FeeWorkflowDtos.BulkDiscountRequest;
import com.indraacademy.ias_management.dto.FeeWorkflowDtos.WorkflowChangeResult;
import com.indraacademy.ias_management.dto.RecalculationEntryDto;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.FeeConfigType;
import com.indraacademy.ias_management.entity.FeeHead;
import com.indraacademy.ias_management.entity.StudentFeeConfig;
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
