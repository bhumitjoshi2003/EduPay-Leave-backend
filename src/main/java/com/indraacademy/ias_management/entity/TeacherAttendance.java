package com.indraacademy.ias_management.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "teacher_attendance",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_teacher_date_school",
                columnNames = {"teacher_id", "date", "school_id"}),
        indexes = {
                @Index(name = "idx_teacher_att_teacher_date", columnList = "teacher_id, date"),
                @Index(name = "idx_teacher_att_school_date", columnList = "school_id, date")
        })
public class TeacherAttendance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "teacher_id", nullable = false)
    private String teacherId;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @JsonFormat(pattern = "yyyy-MM-dd")
    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Column(name = "check_in_time")
    private LocalDateTime checkInTime;

    @Column(name = "check_out_time")
    private LocalDateTime checkOutTime;

    /** ON_TIME, LATE, ABSENT, HALF_DAY, ON_LEAVE */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "distance_from_school")
    private Double distanceFromSchool;

    /** GPS or MANUAL_ADMIN */
    @Column(name = "method", length = 20)
    private String method = "GPS";

    @Column(name = "marked_by_admin")
    private boolean markedByAdmin = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
