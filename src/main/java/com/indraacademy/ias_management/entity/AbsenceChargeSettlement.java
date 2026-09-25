package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * The unapplied-absence fee's paid state for one chargeable ABSENT row (V80) — kept apart from
 * attendance so student_attendance describes attendance only. See AbsenceChargeService.
 */
@Entity
@Table(name = "absence_charge_settlement")
@Getter
@Setter
public class AbsenceChargeSettlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "student_attendance_id", nullable = false, unique = true)
    private Long studentAttendanceId;

    @Column(name = "settled_at", nullable = false)
    private Instant settledAt;
}
