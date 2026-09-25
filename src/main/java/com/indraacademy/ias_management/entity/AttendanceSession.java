package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Attendance V2: one submitted attendance day for a class (sectionId null) or one section of it.
 * Its existence means attendance was taken; every eligible student has an explicit
 * {@link StudentAttendance} row under it. Unique per school + session + class + section + date (V80).
 */
@Entity
@Table(name = "attendance_session")
@Getter
@Setter
public class AttendanceSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private long revision;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "academic_session_id", nullable = false)
    private Long academicSessionId;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(name = "section_id")
    private Long sectionId;

    @Column(name = "attendance_date", nullable = false)
    private LocalDate attendanceDate;

    @Column(name = "marked_by_user_id", nullable = false)
    private String markedByUserId;

    @Column(name = "marked_at", nullable = false)
    private LocalDateTime markedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
