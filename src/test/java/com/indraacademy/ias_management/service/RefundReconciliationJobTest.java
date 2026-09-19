package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Refund;
import com.indraacademy.ias_management.repository.RefundRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Refund-Integrity Hardening, Phase D — unit tests for the scheduled reconciliation batch.
 * {@code reconcileBatch()} (the method under test here) is deliberately separate from the
 * {@code @Scheduled poll()} wrapper specifically so it can be tested without any Spring
 * scheduling machinery — see the class javadoc on {@link RefundReconciliationJob}.
 * <p>
 * Candidate SELECTION correctness itself (excludes non-PENDING rows, excludes a null
 * providerRefundId, respects the age threshold) is a property of the JPQL in
 * {@link RefundRepository#findStalePendingRefundsWithProviderId} — proved against real
 * PostgreSQL in {@code RefundReconciliationJobPostgresIT}, not re-proved here with a mocked
 * repository that would just return whatever this test tells it to.
 */
@ExtendWith(MockitoExtension.class)
class RefundReconciliationJobTest {

    @Mock private RefundRepository refundRepository;
    @Mock private RazorpayService razorpayService;

    private RefundReconciliationJob job;

    @BeforeEach
    void setUp() {
        job = new RefundReconciliationJob(refundRepository, razorpayService);
        ReflectionTestUtils.setField(job, "enabled", true);
        ReflectionTestUtils.setField(job, "pendingMinAgeMinutes", 5L);
        ReflectionTestUtils.setField(job, "batchSize", 25);
    }

    private Refund refund(Long id, String providerRefundId) {
        Refund r = new Refund();
        r.setId(id);
        r.setPaymentId(100L + id);
        r.setStatus(RefundSettlementService.STATUS_PENDING);
        r.setProviderRefundId(providerRefundId);
        return r;
    }

    @Test
    void reconcileBatch_disabled_neverQueriesOrReconciles() {
        ReflectionTestUtils.setField(job, "enabled", false);

        job.poll();

        verifyNoInteractions(refundRepository, razorpayService);
    }

    // Mandatory per the redesign spec: when RefundReconciliationDynamicScheduler owns
    // reconciliation, this legacy poll must return before touching PostgreSQL at all — mirrors
    // NotificationDeliveryWorker.poll()'s and TeacherAttendanceReminderScheduler's own gate.
    @Test
    void dynamicModeEnabled_legacyPollReturnsWithoutTouchingAnyRepositoryOrRazorpay() {
        ReflectionTestUtils.setField(job, "dynamicEnabled", true);

        job.poll();

        verifyNoInteractions(refundRepository, razorpayService);
    }

    @Test
    void dynamicModeDisabled_legacyPollStillScansAsBefore() {
        ReflectionTestUtils.setField(job, "dynamicEnabled", false);
        Refund refund = refund(1L, "rfnd_1");
        when(refundRepository.findStalePendingRefundsWithProviderId(any(), any())).thenReturn(List.of(refund));

        job.poll();

        verify(refundRepository).findStalePendingRefundsWithProviderId(any(), any());
        verify(razorpayService).reconcileRefund(1L);
    }

    @Test
    void reconcileBatch_noCandidates_doesNothing() {
        when(refundRepository.findStalePendingRefundsWithProviderId(any(), any())).thenReturn(List.of());

        job.reconcileBatch();

        verifyNoInteractions(razorpayService);
    }

    @Test
    void reconcileBatch_singleCandidate_callsReconcileRefundWithItsId() {
        when(refundRepository.findStalePendingRefundsWithProviderId(any(), any()))
                .thenReturn(List.of(refund(701L, "rfnd_a")));

        job.reconcileBatch();

        verify(razorpayService).reconcileRefund(701L);
    }

    @Test
    void reconcileBatch_multipleCandidates_processesEachIndependently() {
        when(refundRepository.findStalePendingRefundsWithProviderId(any(), any()))
                .thenReturn(List.of(refund(701L, "rfnd_a"), refund(702L, "rfnd_b"), refund(703L, "rfnd_c")));

        job.reconcileBatch();

        verify(razorpayService).reconcileRefund(701L);
        verify(razorpayService).reconcileRefund(702L);
        verify(razorpayService).reconcileRefund(703L);
    }

    @Test
    void reconcileBatch_oneCandidateThrows_remainingCandidatesStillProcessed() {
        when(refundRepository.findStalePendingRefundsWithProviderId(any(), any()))
                .thenReturn(List.of(refund(701L, "rfnd_a"), refund(702L, "rfnd_b"), refund(703L, "rfnd_c")));
        doThrow(new RuntimeException("unexpected failure")).when(razorpayService).reconcileRefund(702L);

        job.reconcileBatch();

        verify(razorpayService).reconcileRefund(701L);
        verify(razorpayService).reconcileRefund(702L); // attempted
        verify(razorpayService).reconcileRefund(703L); // still reached despite 702's failure
    }

    @Test
    void poll_oneCandidateThrows_pollItselfDoesNotThrow() {
        when(refundRepository.findStalePendingRefundsWithProviderId(any(), any()))
                .thenReturn(List.of(refund(701L, "rfnd_a")));
        doThrow(new RuntimeException("boom")).when(razorpayService).reconcileRefund(701L);

        job.poll(); // must not propagate
    }

    @Test
    void reconcileBatch_usesConfiguredMinimumAgeForThreshold() {
        ReflectionTestUtils.setField(job, "pendingMinAgeMinutes", 15L);
        when(refundRepository.findStalePendingRefundsWithProviderId(any(), any())).thenReturn(List.of());

        LocalDateTime beforeCall = LocalDateTime.now().minusMinutes(15);
        job.reconcileBatch();
        LocalDateTime afterCall = LocalDateTime.now().minusMinutes(15);

        ArgumentCaptor<LocalDateTime> thresholdCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(refundRepository).findStalePendingRefundsWithProviderId(thresholdCaptor.capture(), any());
        assertThat(thresholdCaptor.getValue()).isBetween(beforeCall.minusSeconds(2), afterCall.plusSeconds(2));
    }

    @Test
    void reconcileBatch_usesConfiguredBatchSizeAsPageLimit() {
        ReflectionTestUtils.setField(job, "batchSize", 7);
        when(refundRepository.findStalePendingRefundsWithProviderId(any(), any())).thenReturn(List.of());

        job.reconcileBatch();

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(refundRepository).findStalePendingRefundsWithProviderId(any(), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue()).isEqualTo(PageRequest.of(0, 7));
    }

    @Test
    void poll_reentrantCall_skipsWhileAlreadyRunning() throws Exception {
        // Simulate a slow first pass by blocking inside the mocked reconcileRefund call, then
        // attempt a second poll() from another thread while the first is still "running".
        java.util.concurrent.CountDownLatch insideFirstPoll = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseFirstPoll = new java.util.concurrent.CountDownLatch(1);
        when(refundRepository.findStalePendingRefundsWithProviderId(any(), any()))
                .thenReturn(List.of(refund(701L, "rfnd_a")));
        doAnswer(inv -> {
            insideFirstPoll.countDown();
            releaseFirstPoll.await();
            return null;
        }).when(razorpayService).reconcileRefund(701L);

        Thread firstPoll = new Thread(job::poll);
        firstPoll.start();
        assertThat(insideFirstPoll.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        job.poll(); // must return immediately, not re-enter reconcileBatch

        releaseFirstPoll.countDown();
        firstPoll.join(5000);

        verify(razorpayService, times(1)).reconcileRefund(701L);
    }
}
