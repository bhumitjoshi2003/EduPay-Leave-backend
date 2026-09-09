package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Table(name = "timetable_correction_request")
@Getter @Setter
public class TimetableCorrectionRequest {
    public enum Status { PENDING, APPROVED, REJECTED, CANCELLED }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long schoolId;
    @Column(nullable = false) private Long academicSessionId;
    private Long timetableEntryId;
    @Column(nullable = false) private String requestedByTeacherId;
    @Column(nullable = false) private String expectedCurrentTeacherId;
    @Column(nullable = false) private String requestedTeacherId;
    @Column(nullable = false) private long expectedRevision;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Status status;
    @Column(length = 500) private String reason;
    @Column(nullable = false) private LocalDateTime createdAt;
    private LocalDateTime reviewedAt;
    private String reviewedBy;
}
