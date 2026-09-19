package com.indraacademy.ias_management.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

/**
 * Smallest safe cross-instance execution-ownership guard for
 * {@link TeacherAttendanceReminderDynamicScheduler}: a PostgreSQL session-level advisory lock
 * keyed on (schoolId, localDate), so if two backend instances both dynamically schedule the same
 * school (only possible if this application is ever run with more than one instance — today's
 * production deployment is a single container, see deploy.yml), only one of them actually runs
 * that school's reminder pass for a given day.
 *
 * <p>Deliberately session-scoped ({@code pg_try_advisory_lock}/{@code pg_advisory_unlock} on one
 * borrowed {@link Connection}, held only around the {@code processSchool} call) rather than
 * transaction-scoped — the existing per-teacher notification publishing already manages its own
 * transactions independently, and wrapping all of that inside one new outer transaction just to
 * use the transaction-scoped lock variant would change existing commit granularity for no benefit.
 * This lock is purely an ownership guard; it is never itself the source of truth, and even if it
 * is bypassed entirely (e.g. this method is never reached because the connection cannot be
 * obtained), the existing notification idempotency key
 * ({@code teacher-attendance-reminder:{schoolId}:{localDate}:{teacherId}}) remains the final,
 * always-on protection against a duplicate durable notification.
 */
@org.springframework.stereotype.Component
public class TeacherAttendanceReminderExecutionLock {

    private static final Logger log = LoggerFactory.getLogger(TeacherAttendanceReminderExecutionLock.class);

    public boolean tryAcquire(Connection connection, long schoolId, LocalDate date) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            ps.setLong(1, lockKey(schoolId, date));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    /** Best-effort release — a failure here never propagates, since session-level advisory locks
     * are also released automatically when the borrowed connection is closed/returned. */
    public void release(Connection connection, long schoolId, LocalDate date) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            ps.setLong(1, lockKey(schoolId, date));
            ps.execute();
        } catch (SQLException e) {
            log.warn("Failed to release teacher attendance reminder advisory lock for schoolId={}, date={}: {}",
                    schoolId, date, e.getMessage());
        }
    }

    /**
     * Packs schoolId (assumed to comfortably fit in 32 bits at this application's scale) and the
     * date's epoch-day into one 64-bit advisory lock key, deterministic per (school, day) — the
     * same granularity as the notification idempotency key, so two instances racing the same
     * school's same day's reminder pass serialize on this lock, while different days/schools
     * never contend.
     */
    static long lockKey(long schoolId, LocalDate date) {
        return (schoolId << 32) ^ (date.toEpochDay() & 0xFFFFFFFFL);
    }
}
