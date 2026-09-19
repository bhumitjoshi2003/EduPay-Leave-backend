package com.indraacademy.ias_management.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * In-memory {@code refundId -> ScheduledFuture} bookkeeping for
 * {@link RefundReconciliationDynamicScheduler} — the exact same small, non-durable design as
 * {@link TeacherAttendanceReminderScheduleRegistry}, kept as its own dedicated class (rather than
 * a shared generic one) so this feature's runtime state stays fully isolated from the
 * already-deployed reminder scheduler's.
 *
 * <p>NOT durable truth. PostgreSQL's {@code Refund.status}/{@code providerRefundId} remain
 * authoritative; losing this map's contents (a restart, a crash) only loses in-flight follow-up
 * timing, never financial state — the startup rebuild and the rare safety sweep both recover from
 * PostgreSQL alone.
 */
@Component
public class RefundReconciliationScheduleRegistry {

    private final ConcurrentHashMap<Long, ScheduledFuture<?>> futuresByRefundId = new ConcurrentHashMap<>();

    public void put(Long refundId, ScheduledFuture<?> future) {
        ScheduledFuture<?> previous = futuresByRefundId.put(refundId, future);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    public void cancel(Long refundId) {
        ScheduledFuture<?> existing = futuresByRefundId.remove(refundId);
        if (existing != null) {
            existing.cancel(false);
        }
    }

    public void remove(Long refundId) {
        futuresByRefundId.remove(refundId);
    }

    public boolean isScheduled(Long refundId) {
        return futuresByRefundId.containsKey(refundId);
    }

    public int size() {
        return futuresByRefundId.size();
    }
}
