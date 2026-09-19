package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Refund;
import com.indraacademy.ias_management.repository.RefundRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers the EDUNEXIFY refund-reconciliation-redesign spec's scenarios 22-27: scheduling
 * decisions, after-commit behavior, backoff, startup recovery, webhook/terminal-race safety, and
 * provider-API safety (createRefund must never be invoked from here).
 */
@ExtendWith(MockitoExtension.class)
class RefundReconciliationDynamicSchedulerTest {

    private static final Long REFUND_ID = 500L;

    @Mock private RefundRepository refundRepository;
    @Mock private RazorpayService razorpayService;
    @Mock private TaskScheduler taskScheduler;

    private RefundReconciliationScheduleRegistry registry;
    private RefundReconciliationDynamicScheduler scheduler;
    private Clock clock;

    private Refund refund(String status, String providerRefundId) {
        Refund r = new Refund();
        r.setId(REFUND_ID);
        r.setPaymentId(900L);
        r.setStatus(status);
        r.setProviderRefundId(providerRefundId);
        return r;
    }

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC"));
        registry = new RefundReconciliationScheduleRegistry();
        scheduler = new RefundReconciliationDynamicScheduler();
        ReflectionTestUtils.setField(scheduler, "dynamicEnabled", true);
        ReflectionTestUtils.setField(scheduler, "pendingMinAgeMinutes", 5L);
        ReflectionTestUtils.setField(scheduler, "batchSize", 25);
        ReflectionTestUtils.setField(scheduler, "refundRepository", refundRepository);
        ReflectionTestUtils.setField(scheduler, "razorpayService", razorpayService);
        ReflectionTestUtils.setField(scheduler, "registry", registry);
        ReflectionTestUtils.setField(scheduler, "taskScheduler", taskScheduler);
        ReflectionTestUtils.setField(scheduler, "clock", clock);

        lenient().when(taskScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenAnswer(invocation -> mock(ScheduledFuture.class));
    }

    // ─── Scheduling decisions (scenario 22) ──────────────────────────────────────

    @Test
    void pendingRefundWithProviderId_afterCommit_schedulesFollowUp() {
        when(refundRepository.findById(REFUND_ID)).thenReturn(Optional.of(refund(RefundSettlementService.STATUS_PENDING, "rfnd_1")));

        scheduler.onScheduleChanged(new RefundReconciliationScheduleChangedEvent(REFUND_ID));

        assertThat(registry.isScheduled(REFUND_ID)).isTrue();
    }

    @Test
    void terminalRefund_afterCommit_doesNotSchedule() {
        when(refundRepository.findById(REFUND_ID)).thenReturn(Optional.of(refund(RefundSettlementService.STATUS_SUCCESS, "rfnd_1")));

        scheduler.onScheduleChanged(new RefundReconciliationScheduleChangedEvent(REFUND_ID));

        assertThat(registry.isScheduled(REFUND_ID)).isFalse();
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void missingProviderRefundId_afterCommit_doesNotSchedule() {
        when(refundRepository.findById(REFUND_ID)).thenReturn(Optional.of(refund(RefundSettlementService.STATUS_PENDING, null)));

        scheduler.onScheduleChanged(new RefundReconciliationScheduleChangedEvent(REFUND_ID));

        assertThat(registry.isScheduled(REFUND_ID)).isFalse();
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void duplicateScheduleRequest_cancelsOldFutureBeforeSchedulingNew_noDuplicateActiveFuture() {
        when(refundRepository.findById(REFUND_ID)).thenReturn(Optional.of(refund(RefundSettlementService.STATUS_PENDING, "rfnd_1")));

        scheduler.onScheduleChanged(new RefundReconciliationScheduleChangedEvent(REFUND_ID));
        ScheduledFuture<?> first = peekRegistryFuture();
        scheduler.onScheduleChanged(new RefundReconciliationScheduleChangedEvent(REFUND_ID));

        verify(first).cancel(false);
        assertThat(registry.isScheduled(REFUND_ID)).isTrue();
        verify(taskScheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void terminalTransition_cancelsAnyExistingFuture() {
        when(refundRepository.findById(REFUND_ID)).thenReturn(Optional.of(refund(RefundSettlementService.STATUS_PENDING, "rfnd_1")));
        scheduler.onScheduleChanged(new RefundReconciliationScheduleChangedEvent(REFUND_ID));
        ScheduledFuture<?> pendingFuture = peekRegistryFuture();

        when(refundRepository.findById(REFUND_ID)).thenReturn(Optional.of(refund(RefundSettlementService.STATUS_SUCCESS, "rfnd_1")));
        scheduler.onScheduleChanged(new RefundReconciliationScheduleChangedEvent(REFUND_ID));

        verify(pendingFuture).cancel(false);
        assertThat(registry.isScheduled(REFUND_ID)).isFalse();
    }

    // ─── After-commit gating (scenario 23) ────────────────────────────────────────

    @Test
    void dynamicDisabled_scheduleChangedEvent_doesNothing() {
        ReflectionTestUtils.setField(scheduler, "dynamicEnabled", false);

        scheduler.onScheduleChanged(new RefundReconciliationScheduleChangedEvent(REFUND_ID));

        verifyNoInteractions(refundRepository, taskScheduler);
    }

    // ─── Backoff (scenario 24) ─────────────────────────────────────────────────────

    @Test
    void firstAttempt_stillPending_schedulesSecondAttemptAtSecondBackoffDelay() throws Exception {
        Refund pending = refund(RefundSettlementService.STATUS_PENDING, "rfnd_1");
        when(refundRepository.findById(REFUND_ID)).thenReturn(Optional.of(pending));

        invokeExecuteFollowUp(REFUND_ID, 0);

        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(taskScheduler).schedule(any(Runnable.class), captor.capture());
        Instant expected = Instant.now(clock).plus(RefundReconciliationDynamicScheduler.BACKOFF_DELAYS[1]);
        assertThat(captor.getValue()).isEqualTo(expected);
    }

    @Test
    void lastAttempt_stillPending_stopsActiveFollowUp_noSixthAttemptScheduled() throws Exception {
        Refund pending = refund(RefundSettlementService.STATUS_PENDING, "rfnd_1");
        when(refundRepository.findById(REFUND_ID)).thenReturn(Optional.of(pending));
        int lastIndex = RefundReconciliationDynamicScheduler.BACKOFF_DELAYS.length - 1;

        invokeExecuteFollowUp(REFUND_ID, lastIndex);

        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
        assertThat(registry.isScheduled(REFUND_ID)).isFalse();
    }

    @Test
    void becomesTerminalDuringFollowUp_stopsWithoutSchedulingNext() throws Exception {
        // First read (eligibility check) sees PENDING; the reload AFTER reconcileRefund sees it
        // resolved to SUCCESS — simulating reconcileRefund's own internal finalize.
        Refund pending = refund(RefundSettlementService.STATUS_PENDING, "rfnd_1");
        Refund nowSuccess = refund(RefundSettlementService.STATUS_SUCCESS, "rfnd_1");
        when(refundRepository.findById(REFUND_ID)).thenReturn(Optional.of(pending), Optional.of(nowSuccess));

        invokeExecuteFollowUp(REFUND_ID, 0);

        verify(razorpayService).reconcileRefund(REFUND_ID);
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
        assertThat(registry.isScheduled(REFUND_ID)).isFalse();
    }

    // ─── Startup recovery (scenario 25) ───────────────────────────────────────────

    @Test
    void startupRebuild_disabled_doesNotQuery() {
        ReflectionTestUtils.setField(scheduler, "dynamicEnabled", false);

        scheduler.rebuildSchedulesOnStartup();

        verifyNoInteractions(refundRepository, taskScheduler);
    }

    @Test
    void startupRebuild_schedulesOnlyStaleCandidatesReturnedByTheQuery() {
        // The repository query itself already filters to PENDING + providerRefundId != null +
        // old enough (real-Postgres proof lives in RefundReconciliationJobPostgresIT) — this test
        // proves the scheduler schedules exactly what the query returns, nothing more.
        Refund candidate = refund(RefundSettlementService.STATUS_PENDING, "rfnd_1");
        when(refundRepository.findStalePendingRefundsWithProviderId(any(), any())).thenReturn(List.of(candidate));

        scheduler.rebuildSchedulesOnStartup();

        assertThat(registry.isScheduled(REFUND_ID)).isTrue();
        verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void startupRebuild_noCandidates_schedulesNothing() {
        when(refundRepository.findStalePendingRefundsWithProviderId(any(), any())).thenReturn(List.of());

        scheduler.rebuildSchedulesOnStartup();

        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    // ─── Safety sweep ──────────────────────────────────────────────────────────────

    @Test
    void safetySweep_disabled_doesNothing() {
        ReflectionTestUtils.setField(scheduler, "dynamicEnabled", false);

        scheduler.safetySweep();

        verifyNoInteractions(refundRepository, taskScheduler);
    }

    @Test
    void safetySweep_candidateAlreadyScheduled_doesNotDuplicateSchedule() {
        registry.put(REFUND_ID, mock(ScheduledFuture.class));
        Refund candidate = refund(RefundSettlementService.STATUS_PENDING, "rfnd_1");
        when(refundRepository.findStalePendingRefundsWithProviderId(any(), any())).thenReturn(List.of(candidate));

        scheduler.safetySweep();

        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void safetySweep_candidateNotScheduled_reschedulesIt() {
        Refund candidate = refund(RefundSettlementService.STATUS_PENDING, "rfnd_1");
        when(refundRepository.findStalePendingRefundsWithProviderId(any(), any())).thenReturn(List.of(candidate));

        scheduler.safetySweep();

        assertThat(registry.isScheduled(REFUND_ID)).isTrue();
    }

    // ─── Execution correctness / terminal-race safety (scenario 26) ─────────────

    @Test
    void execution_refundNoLongerExists_removesScheduleAndDoesNotCallRazorpay() throws Exception {
        when(refundRepository.findById(REFUND_ID)).thenReturn(Optional.empty());

        invokeExecuteFollowUp(REFUND_ID, 0);

        verifyNoInteractions(razorpayService);
        assertThat(registry.isScheduled(REFUND_ID)).isFalse();
    }

    @Test
    void execution_alreadyTerminalAtStart_safeNoOp_neverCallsRazorpay() throws Exception {
        // Simulates: a webhook resolved this refund moments before the scheduled task fired.
        when(refundRepository.findById(REFUND_ID)).thenReturn(Optional.of(refund(RefundSettlementService.STATUS_SUCCESS, "rfnd_1")));

        invokeExecuteFollowUp(REFUND_ID, 0);

        verifyNoInteractions(razorpayService);
        assertThat(registry.isScheduled(REFUND_ID)).isFalse();
    }

    @Test
    void execution_missingProviderRefundIdAtFireTime_doesNotCallRazorpay_doesNotReschedule() throws Exception {
        when(refundRepository.findById(REFUND_ID)).thenReturn(Optional.of(refund(RefundSettlementService.STATUS_PENDING, null)));

        invokeExecuteFollowUp(REFUND_ID, 0);

        verifyNoInteractions(razorpayService);
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void dbDownDuringExecution_schedulesNextBoundedBackoffStep_notATightLoop() throws Exception {
        when(refundRepository.findById(REFUND_ID)).thenThrow(new RuntimeException("db down"));

        invokeExecuteFollowUp(REFUND_ID, 0);

        verifyNoInteractions(razorpayService);
        verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void razorpayThrowsDuringReconcile_doesNotCrash_schedulesNextBackoffStep() throws Exception {
        Refund pending = refund(RefundSettlementService.STATUS_PENDING, "rfnd_1");
        when(refundRepository.findById(REFUND_ID)).thenReturn(Optional.of(pending));
        org.mockito.Mockito.doThrow(new RuntimeException("razorpay down")).when(razorpayService).reconcileRefund(REFUND_ID);

        invokeExecuteFollowUp(REFUND_ID, 0);

        verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));
        assertThat(registry.isScheduled(REFUND_ID)).isTrue();
    }

    // ─── Provider API safety (scenario 27) ────────────────────────────────────────

    @Test
    void neverCallsCreateRefund_onlyReconcileRefund() throws Exception {
        Refund pending = refund(RefundSettlementService.STATUS_PENDING, "rfnd_1");
        when(refundRepository.findById(REFUND_ID)).thenReturn(Optional.of(pending));

        invokeExecuteFollowUp(REFUND_ID, 0);

        verify(razorpayService).reconcileRefund(REFUND_ID);
        verify(razorpayService, never()).createRefund(any(), anyLong(), any());
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private ScheduledFuture<?> peekRegistryFuture() {
        var map = (java.util.concurrent.ConcurrentHashMap<Long, ScheduledFuture<?>>)
                ReflectionTestUtils.getField(registry, "futuresByRefundId");
        return map.get(REFUND_ID);
    }

    private void invokeExecuteFollowUp(Long refundId, int attemptIndex) throws Exception {
        var method = RefundReconciliationDynamicScheduler.class
                .getDeclaredMethod("executeFollowUp", Long.class, int.class);
        method.setAccessible(true);
        method.invoke(scheduler, refundId, attemptIndex);
    }
}
