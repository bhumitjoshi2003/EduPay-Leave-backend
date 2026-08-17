package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.SchoolSettingsResponse;
import com.indraacademy.ias_management.dto.SchoolSettingsUpdateRequest;
import com.indraacademy.ias_management.dto.SuperAdminDashboardDto;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.repository.PaymentRepository;
import com.indraacademy.ias_management.repository.RefundRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.service.AuditService;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Integrity-hardening phase: the platform-wide SUPER_ADMIN revenue figure had never been
 * netted against refunds at all — a distinct gap from (and missed by) the earlier
 * school-scoped DashboardService fix, since SchoolService.getSuperAdminDashboard queries the
 * platform-wide Payment/Refund sums, not the school-scoped ones.
 */
@ExtendWith(MockitoExtension.class)
class SchoolServiceTest {

    @Mock private SchoolRepository schoolRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private TeacherRepository teacherRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private RefundRepository refundRepository;
    @Mock private AuditService auditService;
    @Mock private SecurityUtil securityUtil;
    @Mock private HttpServletRequest request;

    private SchoolService service;

    private static final Long SCHOOL_ID = 2L;

    @BeforeEach
    void setUp() {
        service = new SchoolService();
        ReflectionTestUtils.setField(service, "schoolRepository", schoolRepository);
        ReflectionTestUtils.setField(service, "studentRepository", studentRepository);
        ReflectionTestUtils.setField(service, "teacherRepository", teacherRepository);
        ReflectionTestUtils.setField(service, "paymentRepository", paymentRepository);
        ReflectionTestUtils.setField(service, "refundRepository", refundRepository);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);

        lenient().when(schoolRepository.count()).thenReturn(5L);
        lenient().when(schoolRepository.countByActiveTrue()).thenReturn(4L);
        lenient().when(studentRepository.count()).thenReturn(100L);
        lenient().when(teacherRepository.count()).thenReturn(10L);
    }

    @Test
    void superAdminRevenue_noRefunds_equalsGrossPlatformWidePayments() {
        LocalDate today = LocalDate.now();
        when(paymentRepository.sumAmountCollectedByMonthAndYear(today.getMonthValue(), today.getYear()))
                .thenReturn(1_000_000L);
        when(refundRepository.sumAmountPaiseByMonthAndYear(today.getMonthValue(), today.getYear()))
                .thenReturn(0L);

        SuperAdminDashboardDto dto = service.getSuperAdminDashboard();

        assertThat(dto.getRevenueThisMonth()).isEqualTo(1_000_000L);
    }

    @Test
    void superAdminRevenue_afterRefunds_isNetOfRefundsAcrossAllSchools() {
        LocalDate today = LocalDate.now();
        when(paymentRepository.sumAmountCollectedByMonthAndYear(today.getMonthValue(), today.getYear()))
                .thenReturn(1_000_000L);
        when(refundRepository.sumAmountPaiseByMonthAndYear(today.getMonthValue(), today.getYear()))
                .thenReturn(150_000L);

        SuperAdminDashboardDto dto = service.getSuperAdminDashboard();

        assertThat(dto.getRevenueThisMonth()).isEqualTo(850_000L);
        // Never count refunded money as collected revenue — the net figure must be strictly
        // less than the gross figure whenever any refund occurred.
        assertThat(dto.getRevenueThisMonth()).isLessThan(1_000_000L);
    }

    // ─── School.timezone — every teacher-attendance date/time decision depends on this being real ───

    private School existingSchool() {
        School s = new School();
        s.setId(SCHOOL_ID);
        s.setName("Test School");
        s.setTimezone("Asia/Kolkata");
        return s;
    }

    @Test
    void updateSettings_acceptsAValidIanaTimezone() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(existingSchool()));
        when(schoolRepository.save(any(School.class))).thenAnswer(inv -> inv.getArgument(0));

        SchoolSettingsUpdateRequest req = new SchoolSettingsUpdateRequest();
        req.setTimezone("America/Los_Angeles");

        SchoolSettingsResponse response = service.updateSettings(req, request);

        assertThat(response.getTimezone()).isEqualTo("America/Los_Angeles");
    }

    /**
     * A bad timezone string must be rejected at the point of entry — TeacherAttendanceService's
     * own fallback (see its zoneId() Javadoc) is a defensive backstop for data that predates this
     * validation, not a reason to skip validating new writes.
     */
    @Test
    void updateSettings_rejectsAnInvalidTimezone_ratherThanSilentlyStoringIt() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(existingSchool()));

        SchoolSettingsUpdateRequest req = new SchoolSettingsUpdateRequest();
        req.setTimezone("Narnia/Cair_Paravel");

        assertThatThrownBy(() -> service.updateSettings(req, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid timezone");

        org.mockito.Mockito.verify(schoolRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void updateSettings_omittedTimezone_leavesTheExistingValueUnchanged() {
        School existing = existingSchool(); // already "Asia/Kolkata"
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(existing));
        when(schoolRepository.save(any(School.class))).thenAnswer(inv -> inv.getArgument(0));

        SchoolSettingsUpdateRequest req = new SchoolSettingsUpdateRequest();
        req.setName("Renamed School"); // unrelated field changed, timezone untouched

        SchoolSettingsResponse response = service.updateSettings(req, request);

        assertThat(response.getTimezone()).isEqualTo("Asia/Kolkata");
    }
}
