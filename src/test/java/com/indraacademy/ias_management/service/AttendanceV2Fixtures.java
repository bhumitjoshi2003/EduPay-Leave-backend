package com.indraacademy.ias_management.service;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

/**
 * Raw-SQL Attendance V2 fixtures for the Postgres ITs: one explicit student_attendance row inside
 * its class/section submission (attendance_session), creating the submission on first use. The
 * academic session is the school's session covering the date, exactly as the service resolves it.
 */
final class AttendanceV2Fixtures {

    private AttendanceV2Fixtures() {}

    /** Returns the submission id for the exact class/section/date scope, creating it if absent. */
    static long submission(JdbcTemplate jdbc, long schoolId, long classId, Long sectionId, LocalDate date) {
        Long academicSession = jdbc.queryForObject(
                "SELECT id FROM academic_session WHERE school_id=? AND ? BETWEEN start_date AND end_date",
                Long.class, schoolId, date);
        List<Long> existing = jdbc.queryForList(
                "SELECT id FROM attendance_session WHERE school_id=? AND academic_session_id=? AND class_id=? " +
                        "AND section_id IS NOT DISTINCT FROM CAST(? AS BIGINT) AND attendance_date=?",
                Long.class, schoolId, academicSession, classId, sectionId, date);
        if (!existing.isEmpty()) return existing.get(0);
        return jdbc.queryForObject(
                "INSERT INTO attendance_session (school_id,academic_session_id,class_id,section_id,attendance_date," +
                        "marked_by_user_id,marked_at,updated_at) VALUES (?,?,?,?,?,'fixture',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) " +
                        "RETURNING id",
                Long.class, schoolId, academicSession, classId, sectionId, date);
    }

    /** Records one explicit PRESENT/ABSENT row and returns its student_attendance id. */
    static long mark(JdbcTemplate jdbc, long schoolId, String studentId, long classId, Long sectionId,
                     LocalDate date, String status) {
        long submission = submission(jdbc, schoolId, classId, sectionId, date);
        return jdbc.queryForObject(
                "INSERT INTO student_attendance (attendance_session_id,student_id,status,created_at,updated_at) " +
                        "VALUES (?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id",
                Long.class, submission, studentId, status);
    }

    static long countRows(JdbcTemplate jdbc, long schoolId) {
        return jdbc.queryForObject("SELECT count(*) FROM student_attendance a JOIN attendance_session s " +
                "ON s.id=a.attendance_session_id WHERE s.school_id=?", Long.class, schoolId);
    }
}
