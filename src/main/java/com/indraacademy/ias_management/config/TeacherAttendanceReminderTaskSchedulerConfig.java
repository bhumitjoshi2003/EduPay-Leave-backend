package com.indraacademy.ias_management.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * A small, bounded {@link TaskScheduler} dedicated to
 * {@link com.indraacademy.ias_management.service.TeacherAttendanceReminderDynamicScheduler}'s
 * per-school one-shot tasks — the codebase has no general-purpose injectable TaskScheduler bean
 * ({@code @EnableScheduling} only wires up {@code @Scheduled} annotation processing internally,
 * it does not expose one).
 *
 * <p>Pool size of 2 (not 1): a school's reminder pass does real DB + notification-publishing work
 * and can take a moment, so a single busy thread could delay another school's fire time sitting
 * right behind it in the queue. Two is enough headroom for that without opening the door to
 * unbounded thread growth — this scheduler only ever has, at most, one task per reminder-enabled
 * school outstanding at a time. {@code setRemoveOnCancelPolicy(true)} matters here specifically
 * because every settings change cancels-and-replaces that school's future; without it, cancelled
 * tasks would sit in the executor's queue until their original (now-stale) fire time instead of
 * being purged immediately.
 */
@Configuration
public class TeacherAttendanceReminderTaskSchedulerConfig {

    @Bean
    public TaskScheduler teacherAttendanceReminderTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("teacher-attendance-reminder-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
