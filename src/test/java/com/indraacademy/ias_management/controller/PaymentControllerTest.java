package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.CheckoutQuoteDto;
import com.indraacademy.ias_management.dto.CreateOrderRequest;
import com.indraacademy.ias_management.entity.StudentFees;
import com.indraacademy.ias_management.repository.PaymentOrderRepository;
import com.indraacademy.ias_management.repository.PaymentRepository;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.AttendanceService;
import com.indraacademy.ias_management.service.ParentPortalService;
import com.indraacademy.ias_management.service.PaymentService;
import com.indraacademy.ias_management.service.RazorpayService;
import com.indraacademy.ias_management.service.StudentFeesService;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase: Fees/Payments AcademicSession authority audit — Goal 5 (Payment/Razorpay labels).
 *
 * createOrder used to persist req.getClassName() (a raw client-supplied string) verbatim onto
 * PaymentOrder, and from there onto the resulting Payment record at verify time — a parent or
 * student could submit any className and have it permanently recorded against their payment,
 * with nothing checking it against what the student was actually billed under. studentId/
 * session/month were already authoritative (validated via computeCheckoutQuote against real
 * StudentFees rows before order creation, and re-read from the server-persisted PaymentOrder
 * at verify time — never trusted from the client there). className now gets the same treatment:
 * derived from the actual StudentFees row(s) behind the already-validated months.
 */
@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {

    @Mock private RazorpayService razorpayService;
    @Mock private PaymentService paymentService;
    @Mock private AuthService authService;
    @Mock private PaymentRepository paymentRepository;
    @Mock private PaymentOrderRepository paymentOrderRepository;
    @Mock private SecurityUtil securityUtil;
    @Mock private StudentFeesService studentFeesService;
    @Mock private ParentPortalService parentPortalService;
    @Mock private AttendanceService attendanceService;

    private PaymentController controller;

    private static final String STUDENT_ID = "S1";
    private static final String SESSION = "2025-2026";

    @BeforeEach
    void setUp() {
        controller = new PaymentController();
        ReflectionTestUtils.setField(controller, "razorpayService", razorpayService);
        ReflectionTestUtils.setField(controller, "paymentService", paymentService);
        ReflectionTestUtils.setField(controller, "authService", authService);
        ReflectionTestUtils.setField(controller, "paymentRepository", paymentRepository);
        ReflectionTestUtils.setField(controller, "paymentOrderRepository", paymentOrderRepository);
        ReflectionTestUtils.setField(controller, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(controller, "studentFeesService", studentFeesService);
        ReflectionTestUtils.setField(controller, "parentPortalService", parentPortalService);
        ReflectionTestUtils.setField(controller, "attendanceService", attendanceService);

        lenient().when(authService.getRole()).thenReturn(Role.ADMIN);
        lenient().when(attendanceService.getTotalUnappliedLeaveCount(anyString(), anyString())).thenReturn(0L);
        lenient().when(razorpayService.calculateOutstandingBalancePaise(anyString(), anyString())).thenReturn(500000L);
        lenient().when(razorpayService.createOrder(
                anyInt(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(Map.of("orderId", "order_test"));
    }

    private CreateOrderRequest request(String clientClassName, String monthSelection, int totalAmount) {
        CreateOrderRequest req = new CreateOrderRequest();
        req.setTotalAmount(totalAmount);
        req.setStudentId(STUDENT_ID);
        req.setStudentName("Student One");
        req.setClassName(clientClassName);
        req.setSession(SESSION);
        req.setMonthSelectionString(monthSelection);
        req.setTotalBusFee(0);
        req.setTotalTuitionFee(0);
        req.setTotalAnnualCharges(0);
        req.setTotalLabCharges(0);
        req.setTotalEcaProject(0);
        req.setTotalExaminationFee(0);
        req.setAdditionalCharges(0);
        return req;
    }

    private StudentFees fee(int month, String className, BigDecimal baseAmountDue) {
        StudentFees f = new StudentFees();
        f.setStudentId(STUDENT_ID);
        f.setMonth(month);
        f.setClassName(className);
        f.setYear(SESSION);
        f.setBaseAmountDue(baseAmountDue);
        return f;
    }

    @Test
    void createOrder_derivesClassNameFromResolvedStudentFeesRow_ignoresClientSuppliedValue() {
        // Month 6 selected — "010000000000" has bit index 5 set (academic month 6).
        String monthSelection = "000001000000";
        CreateOrderRequest req = request("FORGED_CLASS_12", monthSelection, 2000_00);

        CheckoutQuoteDto quote = new CheckoutQuoteDto();
        quote.setUnresolvedMonths(List.of());
        quote.setTotalAmount(BigDecimal.valueOf(2000));
        quote.setLateFee(BigDecimal.ZERO);
        quote.setPlatformFee(BigDecimal.ZERO);
        when(studentFeesService.computeCheckoutQuote(eq(STUDENT_ID), eq(SESSION), eq(List.of(6))))
                .thenReturn(quote);

        // The student's REAL StudentFees row for month 6 says "6A" — never "FORGED_CLASS_12".
        when(studentFeesService.getStudentFees(STUDENT_ID, SESSION))
                .thenReturn(List.of(fee(6, "6A", BigDecimal.valueOf(2000))));

        ResponseEntity<Map<String, Object>> response = controller.createOrder(req);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        ArgumentCaptor<String> classNameCaptor = ArgumentCaptor.forClass(String.class);
        verify(razorpayService).createOrder(
                anyInt(), eq(STUDENT_ID), anyString(), classNameCaptor.capture(), eq(SESSION), eq(monthSelection),
                any(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
        assertThat(classNameCaptor.getValue()).isEqualTo("6A");
    }

    @Test
    void createOrder_multipleResolvedMonthsWithDifferentClasses_usesTheFirstMatch() {
        // A student paying for two months that happen to span a class change — the server
        // picks one deterministically rather than trusting the client either way.
        String monthSelection = "000001100000"; // months 6 and 7
        CreateOrderRequest req = request("ANYTHING", monthSelection, 4000_00);

        CheckoutQuoteDto quote = new CheckoutQuoteDto();
        quote.setUnresolvedMonths(List.of());
        quote.setTotalAmount(BigDecimal.valueOf(4000));
        quote.setLateFee(BigDecimal.ZERO);
        quote.setPlatformFee(BigDecimal.ZERO);
        when(studentFeesService.computeCheckoutQuote(eq(STUDENT_ID), eq(SESSION), eq(List.of(6, 7))))
                .thenReturn(quote);
        when(studentFeesService.getStudentFees(STUDENT_ID, SESSION))
                .thenReturn(List.of(fee(6, "9", BigDecimal.valueOf(2000)), fee(7, "10", BigDecimal.valueOf(2000))));

        controller.createOrder(req);

        ArgumentCaptor<String> classNameCaptor = ArgumentCaptor.forClass(String.class);
        verify(razorpayService).createOrder(
                anyInt(), eq(STUDENT_ID), anyString(), classNameCaptor.capture(), eq(SESSION), eq(monthSelection),
                any(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
        assertThat(classNameCaptor.getValue()).isIn("9", "10");
    }

    @Test
    void createOrder_rejectsClientManipulatedLeaveCharge() {
        CreateOrderRequest req = request("6A", "100000000000", 1025_00);
        req.setAdditionalCharges(1_000);
        when(attendanceService.getTotalUnappliedLeaveCount(STUDENT_ID, SESSION)).thenReturn(1L);
        CheckoutQuoteDto quote = new CheckoutQuoteDto();
        quote.setUnresolvedMonths(List.of());
        quote.setTotalAmount(BigDecimal.valueOf(1000));
        quote.setLateFee(BigDecimal.ZERO);
        quote.setPlatformFee(BigDecimal.ZERO);
        when(studentFeesService.computeCheckoutQuote(eq(STUDENT_ID), eq(SESSION), any())).thenReturn(quote);

        ResponseEntity<Map<String, Object>> response = controller.createOrder(req);

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
    }
}
