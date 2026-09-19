package com.indraacademy.ias_management.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * In-memory {@code schoolId -> ScheduledFuture} bookkeeping for
 * {@link TeacherAttendanceReminderDynamicScheduler} — nothing more than a lookup so a school's
 * previously-scheduled task can be found and cancelled before a new one replaces it.
 *
 * <p>This map is NOT durable truth. It holds no information PostgreSQL doesn't already hold more
 * authoritatively (school.teacherAttendanceReminderEnabled / .teacherAttendanceReminderTime /
 * .timezone) and is rebuilt from scratch, from PostgreSQL, on every backend restart. Losing its
 * contents (a restart, a crash) loses no state — it only loses in-flight scheduling, which the
 * next startup rebuild recreates.
 */
@Component
public class TeacherAttendanceReminderScheduleRegistry {

    private final ConcurrentHashMap<Long, ScheduledFuture<?>> futuresBySchoolId = new ConcurrentHashMap<>();

    public void put(Long schoolId, ScheduledFuture<?> future) {
        ScheduledFuture<?> previous = futuresBySchoolId.put(schoolId, future);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    /** Cancels and removes any currently-scheduled task for this school, if one exists. */
    public void cancel(Long schoolId) {
        ScheduledFuture<?> existing = futuresBySchoolId.remove(schoolId);
        if (existing != null) {
            existing.cancel(false);
        }
    }

    /** Removes the bookkeeping entry without cancelling — used when a task has already fired and is done. */
    public void remove(Long schoolId) {
        futuresBySchoolId.remove(schoolId);
    }

    public int size() {
        return futuresBySchoolId.size();
    }

    public boolean isScheduled(Long schoolId) {
        return futuresBySchoolId.containsKey(schoolId);
    }
}
