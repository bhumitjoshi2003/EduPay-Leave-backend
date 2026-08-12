package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.StudentFees;
import com.indraacademy.ias_management.repository.StudentFeesRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Consumer-migration coverage: calculateOutstandingBalancePaise (the payment-amount ceiling
 * validated on POST /api/payments/create) used to estimate a flat per-month figure from the
 * legacy FeeStructure table, applied uniformly to every unpaid row regardless of its actual
 * class/month. It now sums each unpaid row's own resolved StudentFees snapshot — the same
 * figure checkout/reminders use — via FeeCalculationService.resolveSchoolFeeDue.
 */
@ExtendWith(MockitoExtension.class)
class RazorpayServiceTest {

    @Mock private StudentFeesRepository studentFeesRepository;
    @Mock private FeeCalculationService feeCalculationService;
    @Mock private SecurityUtil securityUtil;

    private RazorpayService service;

    private static final Long SCHOOL_ID = 1L;
    private static final String SESSION = "2025-2026";

    @BeforeEach
    void setUp() {
        service = new RazorpayService();
        ReflectionTestUtils.setField(service, "studentFeesRepository", studentFeesRepository);
        ReflectionTestUtils.setField(service, "feeCalculationService", feeCalculationService);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
    }

    private StudentFees unpaidRow(int month, String className) {
        StudentFees fee = new StudentFees();
        fee.setStudentId("S1");
        fee.setSchoolId(SCHOOL_ID);
        fee.setYear(SESSION);
        fee.setMonth(month);
        fee.setClassName(className);
        fee.setPaid(false);
        return fee;
    }

    @Test
    void ceilingUsesEachRowsOwnResolvedSnapshotAmount_notAFlatEstimateFromTheFirstRowsClass() {
        // Two rows, DIFFERENT classes — the old implementation used only the first row's
        // class for a flat legacy-FeeStructure estimate applied to every row; the new one
        // must resolve each row independently.
        StudentFees row1 = unpaidRow(1, "5A");
        StudentFees row2 = unpaidRow(2, "6B");
        when(studentFeesRepository.findByStudentIdAndSchoolIdAndPaidFalse("S1", SCHOOL_ID)).thenReturn(List.of(row1, row2));
        when(feeCalculationService.resolveSchoolFeeDue(row1, SCHOOL_ID, SESSION)).thenReturn(Optional.of(BigDecimal.valueOf(2000)));
        when(feeCalculationService.resolveSchoolFeeDue(row2, SCHOOL_ID, SESSION)).thenReturn(Optional.of(BigDecimal.valueOf(3500)));

        long ceilingPaise = service.calculateOutstandingBalancePaise("S1", SESSION);

        // ceiling = ((2000+lateBuffer) + (3500+lateBuffer)) * 1.2, at minimum > the raw sum
        // in paise — confirms real per-row amounts were used, not a flat legacy estimate.
        assertThat(ceilingPaise).isGreaterThan(550_000L); // > (2000+3500)*100 paise
    }

    @Test
    void unresolvableRowStillContributesAFallbackEstimate_neverShrinksTheCeilingToZero() {
        StudentFees row = unpaidRow(1, "5A");
        when(studentFeesRepository.findByStudentIdAndSchoolIdAndPaidFalse("S1", SCHOOL_ID)).thenReturn(List.of(row));
        when(feeCalculationService.resolveSchoolFeeDue(row, SCHOOL_ID, SESSION)).thenReturn(Optional.empty());

        long ceilingPaise = service.calculateOutstandingBalancePaise("S1", SESSION);

        // Must still be a real, generous ceiling — not 0 or near-0 just because one row's
        // exact amount is unknown (would wrongly reject a legitimate payment).
        assertThat(ceilingPaise).isGreaterThan(500_000L);
    }

    @Test
    void noUnpaidFeesReturnsZero() {
        when(studentFeesRepository.findByStudentIdAndSchoolIdAndPaidFalse("S1", SCHOOL_ID)).thenReturn(List.of());

        assertThat(service.calculateOutstandingBalancePaise("S1", SESSION)).isZero();
    }
}
