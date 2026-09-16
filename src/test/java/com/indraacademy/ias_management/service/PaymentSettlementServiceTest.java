package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Payment;
import com.indraacademy.ias_management.entity.PaymentOrder;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.PaymentOrderRepository;
import com.indraacademy.ias_management.repository.PaymentRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * PaymentSettlementService owns exactly the financial-write half of what
 * RazorpayService.verifyPayment used to do inline — everything here is deterministic logic
 * (idempotency branching, field mapping, lock-order sequencing) that Mockito can honestly
 * verify. What it CANNOT honestly verify — real transactional rollback and real concurrent
 * locking — is covered instead by PaymentSettlementServicePostgresIT against a real database.
 */
@ExtendWith(MockitoExtension.class)
class PaymentSettlementServiceTest {

    @Mock PaymentOrderRepository paymentOrderRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock StudentRepository studentRepository;
    @Mock AttendanceService attendanceService;
    @Mock StudentFeesService studentFeesService;
    @InjectMocks PaymentSettlementService service;

    private static final Long SCHOOL_ID = 1L;
    private static final String ORDER_ID = "order_ABC123";
    private static final String PAYMENT_ID = "pay_XYZ789";
    private static final String SIGNATURE = "sig123";

    private PaymentOrder freshOrder() {
        PaymentOrder order = new PaymentOrder();
        order.setOrderId(ORDER_ID);
        order.setSchoolId(SCHOOL_ID);
        order.setStudentId("S1");
        order.setClassName("6A");
        order.setSession("2025-2026");
        order.setMonth("001000000000");
        order.setAmount(250000);
        order.setBusFee(0);
        order.setConsumed(false);
        return order;
    }

    // ── Case 3: fresh order + fresh paymentId — successful settlement ──────────────────────

    @Test
    void successfulSettlement_createsPayment_consumesOrder_marksFeesPaid_updatesAttendance() {
        PaymentOrder order = freshOrder();
        when(paymentRepository.existsByPaymentId(PAYMENT_ID)).thenReturn(false);
        when(paymentOrderRepository.findByOrderIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID))
                .thenReturn(Optional.of(student("S1", "Test Student")));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(500L);
            return p;
        });

        PaymentSettlementService.SettlementResult result = service.settle(ORDER_ID, PAYMENT_ID, SIGNATURE, SCHOOL_ID, PaymentSettlementService.SettlementSource.CLIENT_VERIFY);

        assertThat(result.outcome()).isEqualTo(PaymentSettlementService.Outcome.SETTLED);
        assertThat(result.message()).isEqualTo("Payment Verified Successfully");
        assertThat(result.payment()).isNotNull();
        assertThat(result.payment().getId()).isEqualTo(500L);

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(paymentCaptor.capture());
        Payment saved = paymentCaptor.getValue();
        assertThat(saved.getStudentId()).isEqualTo("S1");
        assertThat(saved.getStudentName()).isEqualTo("Test Student");
        assertThat(saved.getPaymentId()).isEqualTo(PAYMENT_ID);
        assertThat(saved.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(saved.getAmount()).isEqualTo(250000);
        assertThat(saved.getAmountPaid()).isEqualTo(250000);
        assertThat(saved.getRazorpaySignature()).isEqualTo(SIGNATURE);
        assertThat(saved.getSchoolId()).isEqualTo(SCHOOL_ID);
        assertThat(saved.isPaidManually()).isFalse();

        assertThat(order.isConsumed()).isTrue();
        verify(paymentOrderRepository).save(order);
        // Fix B: the trusted, already-validated schoolId is passed explicitly — never
        // re-derived from ambient SecurityUtil/SchoolContext, which is never populated for a
        // genuine /api/webhooks/* settlement.
        verify(attendanceService).updateChargePaidAfterPayment("S1", "2025-2026", SCHOOL_ID);
        verify(studentFeesService).markFeesAsPaid(any(Payment.class));
    }

    @Test
    void lockOrder_paymentOrderIsLockedBeforeStudentFeesIsTouched() {
        PaymentOrder order = freshOrder();
        when(paymentRepository.existsByPaymentId(PAYMENT_ID)).thenReturn(false);
        when(paymentOrderRepository.findByOrderIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        service.settle(ORDER_ID, PAYMENT_ID, SIGNATURE, SCHOOL_ID, PaymentSettlementService.SettlementSource.CLIENT_VERIFY);

        InOrder order1 = inOrder(paymentOrderRepository, studentFeesService);
        order1.verify(paymentOrderRepository).findByOrderIdForUpdate(ORDER_ID);
        order1.verify(studentFeesService).markFeesAsPaid(any(Payment.class));
    }

    // ── Case 1: same payment_id already exists ──────────────────────────────────────────────

    @Test
    void samePaymentIdAlreadyExists_preLock_returnsAlreadySettled_neverLocksOrder() {
        when(paymentRepository.existsByPaymentId(PAYMENT_ID)).thenReturn(true);

        PaymentSettlementService.SettlementResult result = service.settle(ORDER_ID, PAYMENT_ID, SIGNATURE, SCHOOL_ID, PaymentSettlementService.SettlementSource.CLIENT_VERIFY);

        assertThat(result.outcome()).isEqualTo(PaymentSettlementService.Outcome.ALREADY_SETTLED);
        assertThat(result.message()).isEqualTo("Payment already verified.");
        assertThat(result.payment()).isNull();
        verifyNoInteractions(paymentOrderRepository, attendanceService, studentFeesService);
    }

    @Test
    void samePaymentIdAlreadyExists_detectedOnlyUnderLock_returnsAlreadySettled() {
        PaymentOrder order = freshOrder();
        // False on the cheap pre-lock check, true once re-checked under the lock — a
        // concurrent settlement for this exact paymentId landed in between.
        when(paymentRepository.existsByPaymentId(PAYMENT_ID)).thenReturn(false, true);
        when(paymentOrderRepository.findByOrderIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));

        PaymentSettlementService.SettlementResult result = service.settle(ORDER_ID, PAYMENT_ID, SIGNATURE, SCHOOL_ID, PaymentSettlementService.SettlementSource.CLIENT_VERIFY);

        assertThat(result.outcome()).isEqualTo(PaymentSettlementService.Outcome.ALREADY_SETTLED);
        verify(paymentRepository, never()).save(any());
        verifyNoInteractions(attendanceService, studentFeesService);
    }

    // ── Case 2: consumed order, different payment_id ────────────────────────────────────────

    @Test
    void consumedOrderWithDifferentPaymentId_isRejected_notSilentlySucceeded() {
        PaymentOrder order = freshOrder();
        order.setConsumed(true);
        when(paymentRepository.existsByPaymentId(PAYMENT_ID)).thenReturn(false);
        when(paymentOrderRepository.findByOrderIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));

        PaymentSettlementService.SettlementResult result = service.settle(ORDER_ID, PAYMENT_ID, SIGNATURE, SCHOOL_ID, PaymentSettlementService.SettlementSource.CLIENT_VERIFY);

        assertThat(result.outcome()).isEqualTo(PaymentSettlementService.Outcome.REJECTED);
        assertThat(result.message()).contains("already used");
        verify(paymentRepository, never()).save(any());
        verifyNoInteractions(attendanceService, studentFeesService);
    }

    // ── Unknown order / wrong school ────────────────────────────────────────────────────────

    @Test
    void unknownOrder_isRejected() {
        when(paymentRepository.existsByPaymentId(PAYMENT_ID)).thenReturn(false);
        when(paymentOrderRepository.findByOrderIdForUpdate(ORDER_ID)).thenReturn(Optional.empty());

        PaymentSettlementService.SettlementResult result = service.settle(ORDER_ID, PAYMENT_ID, SIGNATURE, SCHOOL_ID, PaymentSettlementService.SettlementSource.CLIENT_VERIFY);

        assertThat(result.outcome()).isEqualTo(PaymentSettlementService.Outcome.REJECTED);
        assertThat(result.message()).contains("Unknown order");
    }

    @Test
    void wrongSchool_isRejected() {
        PaymentOrder order = freshOrder();
        order.setSchoolId(999L);
        when(paymentRepository.existsByPaymentId(PAYMENT_ID)).thenReturn(false);
        when(paymentOrderRepository.findByOrderIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));

        PaymentSettlementService.SettlementResult result = service.settle(ORDER_ID, PAYMENT_ID, SIGNATURE, SCHOOL_ID, PaymentSettlementService.SettlementSource.CLIENT_VERIFY);

        assertThat(result.outcome()).isEqualTo(PaymentSettlementService.Outcome.REJECTED);
        assertThat(result.message()).contains("does not belong to this school");
        verify(paymentRepository, never()).save(any());
    }

    // ── Task 6: data-integrity race on save() — distinguish same-payment vs different-payment ─

    @Test
    void saveConflict_paymentIdNowExists_treatedAsAlreadySettled() {
        PaymentOrder order = freshOrder();
        // false pre-lock, false again under the lock (so we actually reach save()), true only
        // once handleSaveConflict re-checks after the DataIntegrityViolationException.
        when(paymentRepository.existsByPaymentId(PAYMENT_ID)).thenReturn(false, false, true);
        when(paymentOrderRepository.findByOrderIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenThrow(new DataIntegrityViolationException("dup payment_id"));

        PaymentSettlementService.SettlementResult result = service.settle(ORDER_ID, PAYMENT_ID, SIGNATURE, SCHOOL_ID, PaymentSettlementService.SettlementSource.CLIENT_VERIFY);

        assertThat(result.outcome()).isEqualTo(PaymentSettlementService.Outcome.ALREADY_SETTLED);
        verifyNoInteractions(attendanceService, studentFeesService);
    }

    @Test
    void saveConflict_paymentIdStillAbsent_rejectedAsDifferentPaymentCollision() {
        PaymentOrder order = freshOrder();
        // Never becomes true — the constraint that fired was V62's order_id one, for a
        // DIFFERENT paymentId than ours.
        when(paymentRepository.existsByPaymentId(PAYMENT_ID)).thenReturn(false);
        when(paymentOrderRepository.findByOrderIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenThrow(new DataIntegrityViolationException("dup order_id"));

        PaymentSettlementService.SettlementResult result = service.settle(ORDER_ID, PAYMENT_ID, SIGNATURE, SCHOOL_ID, PaymentSettlementService.SettlementSource.CLIENT_VERIFY);

        assertThat(result.outcome()).isEqualTo(PaymentSettlementService.Outcome.REJECTED);
        assertThat(result.message()).contains("already used");
        verifyNoInteractions(attendanceService, studentFeesService);
        verify(paymentOrderRepository, never()).save(eq(order));
    }

    private Student student(String studentId, String name) {
        Student s = new Student();
        s.setStudentId(studentId);
        s.setName(name);
        return s;
    }
}
