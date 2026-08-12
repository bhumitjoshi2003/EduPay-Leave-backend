package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.FeePaymentDto;
import com.indraacademy.ias_management.dto.RecordPaymentRequest;
import com.indraacademy.ias_management.entity.FeePayment;
import com.indraacademy.ias_management.entity.PaymentMode;
import com.indraacademy.ias_management.entity.PaymentStatus;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.exception.SystemBFrozenException;
import com.indraacademy.ias_management.repository.FeePaymentRepository;
import com.indraacademy.ias_management.repository.InvoiceRepository;
import com.indraacademy.ias_management.repository.PaymentAllocationRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * System B freeze phase: FeePaymentService.recordPayment must reject every call —
 * StudentFees/Payment/allocation/refund is now the sole canonical financial system — while
 * every read path (getPayment, getPaymentHistory, getStudentPaymentHistory) continues to
 * serve existing FeePayment/PaymentAllocation history exactly as before. No table, entity,
 * or row is touched by this phase.
 */
@ExtendWith(MockitoExtension.class)
class FeePaymentServiceTest {

    @Mock private FeePaymentRepository paymentRepository;
    @Mock private InvoiceRepository invoiceRepository;
    @Mock private PaymentAllocationRepository allocationRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private AuditService auditService;
    @Mock private SecurityUtil securityUtil;
    @Mock private RazorpayService razorpayService;
    @Mock private HttpServletRequest httpServletRequest;

    private FeePaymentService service;

    private static final Long SCHOOL_ID = 1L;

    @BeforeEach
    void setUp() {
        service = new FeePaymentService();
        ReflectionTestUtils.setField(service, "paymentRepository", paymentRepository);
        ReflectionTestUtils.setField(service, "invoiceRepository", invoiceRepository);
        ReflectionTestUtils.setField(service, "allocationRepository", allocationRepository);
        ReflectionTestUtils.setField(service, "studentRepository", studentRepository);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(service, "razorpayService", razorpayService);
        ReflectionTestUtils.setField(service, "objectMapper", new com.fasterxml.jackson.databind.ObjectMapper());

        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
    }

    // ─── Write path frozen ────────────────────────────────────────────────────────────

    @Test
    void recordPayment_manualMode_rejectedByFreeze() {
        RecordPaymentRequest request = new RecordPaymentRequest();
        request.setStudentId("S1");
        request.setAmount(50_000L);
        request.setPaymentMode("CASH");
        request.setInvoiceAllocations(List.of());

        assertThatThrownBy(() -> service.recordPayment(request, httpServletRequest))
                .isInstanceOf(SystemBFrozenException.class)
                .hasMessageContaining("recording a fee payment")
                .hasMessageContaining("StudentFees");

        // No side effect of any kind — the guard fires before any lookup, save, or audit call.
        verifyNoInteractions(paymentRepository, invoiceRepository, allocationRepository, studentRepository, auditService, razorpayService);
    }

    @Test
    void recordPayment_razorpayModeWithValidLookingSignature_stillRejectedByFreeze() {
        // Confirms the freeze applies uniformly regardless of payment mode — a caller
        // cannot route around it by choosing RAZORPAY instead of a manual mode.
        RecordPaymentRequest request = new RecordPaymentRequest();
        request.setStudentId("S1");
        request.setAmount(50_000L);
        request.setPaymentMode("RAZORPAY");
        request.setRazorpayOrderId("order_1");
        request.setRazorpayPaymentId("pay_1");
        request.setRazorpaySignature("sig_1");
        request.setInvoiceAllocations(List.of());

        assertThatThrownBy(() -> service.recordPayment(request, httpServletRequest))
                .isInstanceOf(SystemBFrozenException.class);

        verify(razorpayService, never()).verifyPaymentSignatureForCurrentSchool(any(), any(), any());
        verify(paymentRepository, never()).save(any());
    }

    // ─── Read paths remain fully intact ──────────────────────────────────────────────

    @Test
    void getPayment_existingHistoricalPayment_stillReadableAfterFreeze() {
        FeePayment payment = new FeePayment();
        payment.setId(10L);
        payment.setSchoolId(SCHOOL_ID);
        payment.setStudentId("S1");
        payment.setAmount(200_000L);
        payment.setPaymentMode(PaymentMode.MANUAL_CASH);
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setPaymentDate(LocalDateTime.now().minusDays(30));
        when(paymentRepository.findByIdAndSchoolId(10L, SCHOOL_ID)).thenReturn(Optional.of(payment));
        Student student = new Student();
        student.setStudentId("S1");
        student.setName("Student One");
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(Optional.of(student));

        FeePaymentDto dto = service.getPayment(10L);

        assertThat(dto.getId()).isEqualTo(10L);
        assertThat(dto.getAmount()).isEqualTo(200_000L);
        assertThat(dto.getStudentName()).isEqualTo("Student One");
    }

    @Test
    void getPaymentHistory_returnsExistingRecordsUnaffectedByFreeze() {
        FeePayment payment = new FeePayment();
        payment.setId(11L);
        payment.setSchoolId(SCHOOL_ID);
        payment.setStudentId("S1");
        payment.setAmount(100_000L);
        payment.setPaymentMode(PaymentMode.UPI);
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setPaymentDate(LocalDateTime.now().minusDays(5));
        when(paymentRepository.findFiltered(eq(SCHOOL_ID), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(payment)));
        when(studentRepository.findByStudentIdInAndSchoolId(any(), eq(SCHOOL_ID))).thenReturn(List.of());

        var page = service.getPaymentHistory(null, null, null, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getId()).isEqualTo(11L);
    }
}
