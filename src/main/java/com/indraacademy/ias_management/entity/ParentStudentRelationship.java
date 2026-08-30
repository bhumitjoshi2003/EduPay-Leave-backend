package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "parent_student_relationship", uniqueConstraints = {
        @UniqueConstraint(name = "uk_parent_student_school", columnNames = {"school_id", "parent_id", "student_id"})
}, indexes = {
        @Index(name = "idx_parent_student_parent", columnList = "school_id, parent_id, active"),
        @Index(name = "idx_parent_student_student", columnList = "school_id, student_id, active")
})
@Data
public class ParentStudentRelationship {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "parent_id", nullable = false, length = 50)
    private String parentId;

    @Column(name = "student_id", nullable = false, length = 50)
    private String studentId;

    @Column(name = "relationship_type", nullable = false, length = 30)
    private String relationshipType;

    @Column(name = "primary_guardian", nullable = false)
    private boolean primaryGuardian;

    @Column(name = "can_view_attendance", nullable = false)
    private boolean canViewAttendance = true;

    @Column(name = "can_view_fees", nullable = false)
    private boolean canViewFees = true;

    @Column(name = "can_pay_fees", nullable = false)
    private boolean canPayFees = true;

    @Column(name = "can_view_results", nullable = false)
    private boolean canViewResults = true;

    @Column(name = "can_view_timetable", nullable = false)
    private boolean canViewTimetable = true;

    @Column(name = "can_manage_leave", nullable = false)
    private boolean canManageLeave = true;

    @Column(name = "pickup_authorized", nullable = false)
    private boolean pickupAuthorized;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom = LocalDate.now();

    @Column(name = "effective_until")
    private LocalDate effectiveUntil;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
