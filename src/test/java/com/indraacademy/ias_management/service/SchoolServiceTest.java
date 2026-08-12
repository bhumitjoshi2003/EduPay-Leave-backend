package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.SuperAdminDashboardDto;
import com.indraacademy.ias_management.repository.PaymentRepository;
import com.indraacademy.ias_management.repository.RefundRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
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

    private SchoolService service;

    @BeforeEach
    void setUp() {
        service = new SchoolService();
        ReflectionTestUtils.setField(service, "schoolRepository", schoolRepository);
        ReflectionTestUtils.setField(service, "studentRepository", studentRepository);
        ReflectionTestUtils.setField(service, "teacherRepository", teacherRepository);
        ReflectionTestUtils.setField(service, "paymentRepository", paymentRepository);
        ReflectionTestUtils.setField(service, "refundRepository", refundRepository);

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
}
