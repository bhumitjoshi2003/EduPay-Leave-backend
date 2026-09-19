package com.indraacademy.ias_management.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * A small, bounded {@link TaskScheduler} dedicated to
 * {@link com.indraacademy.ias_management.service.RefundReconciliationDynamicScheduler}'s
 * per-refund one-shot follow-up tasks — deliberately separate from
 * {@code TeacherAttendanceReminderTaskSchedulerConfig}'s scheduler bean, matching this
 * codebase's established pattern of one small dedicated scheduler per feature rather than a
 * shared general-purpose one.
 *
 * <p>Pool size of 1 (not 2, unlike the reminder scheduler): refunds are explicitly rare —
 * "intentional/manual refunds are extremely rare" per operational policy — so the realistic
 * number of refund follow-up tasks outstanding at any given moment is 0 or 1, occasionally 2
 * for a rare pair of concurrent partial refunds. A single thread is enough; there is no
 * meaningful concurrency to protect against a slow task delaying another the way the
 * once-daily-per-school reminder scheduler needed headroom for.
 */
@Configuration
public class RefundReconciliationTaskSchedulerConfig {

    @Bean
    public TaskScheduler refundReconciliationTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("refund-reconciliation-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
