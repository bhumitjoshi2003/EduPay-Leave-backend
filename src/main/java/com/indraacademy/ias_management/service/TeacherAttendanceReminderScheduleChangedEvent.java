package com.indraacademy.ias_management.service;

/**
 * "A school's teacher attendance reminder settings may have changed" — published unconditionally
 * from {@code SchoolService.updateSettings} at the end of every settings save, regardless of
 * whether the reminder-related fields (enabled/time/timezone) were actually touched in that call.
 * Carries no payload beyond the school id on purpose: the listener always reloads the school's
 * current, authoritative settings from PostgreSQL rather than trusting anything about "what
 * changed" carried on the event itself.
 *
 * <p>Consumed only via a {@code @TransactionalEventListener(phase = AFTER_COMMIT)} in
 * {@link TeacherAttendanceReminderDynamicScheduler} — the in-memory schedule for this school must
 * never be rebuilt before the settings-update transaction has actually committed, or a rolled-back
 * update would incorrectly mutate live runtime scheduling.
 */
public record TeacherAttendanceReminderScheduleChangedEvent(Long schoolId) {
}
