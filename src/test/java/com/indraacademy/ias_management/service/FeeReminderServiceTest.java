package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.OverdueStudentDto;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.SnapshotStatus;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentFees;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.repository.PaymentRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentFeesRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Consumer-migration coverage: FeeReminderService.getOverdueStudents used to recompute
 * totalDue live via FeeCalculationService/legacy FeeStructure; it now sums each unpaid
 * row's own StudentFees snapshot via FeeCalculationService.resolveSchoolFeeDue — the same
 * figure payment/checkout use, so reminders and the AI tools that consume this endpoint's
 * totalDue field see the identical number. If any one unpaid month can't be resolved, the
 * WHOLE student's totalDue becomes null (never a partial/understated sum) — the existing
 * "null means unknown, not ₹0" convention this method already documented.
 */
@ExtendWith(MockitoExtension.class)
class FeeReminderServiceTest {

    @Mock private StudentFeesRepository studentFeesRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private SchoolRepository schoolRepository;
    @Mock private FeeCalculationService feeCalculationService;
    @Mock private PaymentRepository paymentRepository;
    @Mock private SecurityUtil securityUtil;

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
        ReflectionTestUtils.setField(service, "paymentRepository", paymentRepository);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);

        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        School school = new School();
        school.setId(SCHOOL_ID);
        school.setAcademicYearStartMonth(4);
        lenient().when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school));
        lenient().when(paymentRepository.findLatestPaymentDateByStudentIdAndSchoolIdAndSession(any(), any(), any()))
                .thenReturn(Optional.empty());
        // FeeReminderService delegates parseSession/academicMonthStart to FeeCalculationService
        // (relocated there in an earlier phase) — since feeCalculationService is mocked here,
        // these need real stubbed behavior, not the calculation methods this test targets.
        lenient().when(feeCalculationService.parseSession(SESSION)).thenReturn(new int[]{2025, 2026});
        lenient().when(feeCalculationService.academicMonthStart(anyInt(), anyInt(), anyInt(), anyInt()))
                .thenAnswer(inv -> {
                    int academicMonth = inv.getArgument(0);
                    int startYear = inv.getArgument(1);
                    int endYear = inv.getArgument(2);
                    int startMonth = inv.getArgument(3);
                    int calendarMonth = ((startMonth - 1 + academicMonth - 1) % 12) + 1;
                    int year = calendarMonth >= startMonth ? startYear : endYear;
                    return java.time.LocalDate.of(year, calendarMonth, 1);
                });
    }

    private Student activeStudent(String studentId, String className) {
        Student s = new Student();
        s.setStudentId(studentId);
        s.setClassName(className);
        s.setStatus(StudentStatus.ACTIVE);
        s.setName("Test Student");
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

    @Test
    void totalDue_sumsEachRowsOwnResolvedSnapshot_notALiveRecompute() {
        StudentFees month1 = unpaidRow("S1", 1, BigDecimal.valueOf(2000), BigDecimal.ZERO, BigDecimal.ZERO, SnapshotStatus.COMPUTED);
        StudentFees month2 = unpaidRow("S1", 2, BigDecimal.valueOf(2000), BigDecimal.valueOf(800), BigDecimal.valueOf(200), SnapshotStatus.COMPUTED);
        when(studentFeesRepository.findAllUnpaidBySchoolIdAndSession(SCHOOL_ID, SESSION)).thenReturn(List.of(month1, month2));
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(Optional.of(activeStudent("S1", "5A")));
        when(feeCalculationService.resolveSchoolFeeDue(month1, SCHOOL_ID, SESSION)).thenReturn(Optional.of(BigDecimal.valueOf(2000)));
        when(feeCalculationService.resolveSchoolFeeDue(month2, SCHOOL_ID, SESSION)).thenReturn(Optional.of(BigDecimal.valueOf(2600))); // 2000+800-200

        List<OverdueStudentDto> result = service.getOverdueStudents(SESSION, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTotalDue()).isEqualTo(4600.0); // 2000 + 2600
        // Never calls the old live-recompute methods directly on the calculation service —
        // only the unified resolveSchoolFeeDue.
        verify(feeCalculationService, never()).loadActiveRules(any(), any(), any());
        verify(feeCalculationService, never()).calculateMonthFeeRupees(anyInt(), any());
    }

    @Test
    void totalDue_isNullWhenAnySingleMonthIsUnresolvable_neverAPartialSum() {
        StudentFees resolvable = unpaidRow("S1", 1, BigDecimal.valueOf(2000), BigDecimal.ZERO, BigDecimal.ZERO, SnapshotStatus.COMPUTED);
        StudentFees unresolvable = unpaidRow("S1", 2, null, null, null, null); // legacy row, no live rules either
        when(studentFeesRepository.findAllUnpaidBySchoolIdAndSession(SCHOOL_ID, SESSION)).thenReturn(List.of(resolvable, unresolvable));
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(Optional.of(activeStudent("S1", "5A")));
        when(feeCalculationService.resolveSchoolFeeDue(resolvable, SCHOOL_ID, SESSION)).thenReturn(Optional.of(BigDecimal.valueOf(2000)));
        when(feeCalculationService.resolveSchoolFeeDue(unresolvable, SCHOOL_ID, SESSION)).thenReturn(Optional.empty());

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
        when(feeCalculationService.resolveSchoolFeeDue(waived, SCHOOL_ID, SESSION)).thenReturn(Optional.of(BigDecimal.ZERO));

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
}
