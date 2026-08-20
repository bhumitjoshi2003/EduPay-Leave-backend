package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "school_fee_settings", uniqueConstraints = @UniqueConstraint(columnNames = "school_id"))
@Data
public class SchoolFeeSettings {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "school_id", nullable = false)
    private Long schoolId;
    @Enumerated(EnumType.STRING)
    @Column(name = "operational_status", nullable = false, length = 20)
    private FeeOperationalStatus operationalStatus = FeeOperationalStatus.DISABLED;
    @Column(name = "activation_date")
    private LocalDate activationDate;
    @Enumerated(EnumType.STRING)
    @Column(name = "mid_session_policy", nullable = false, length = 30)
    private MidSessionFeePolicy midSessionPolicy = MidSessionFeePolicy.FROM_EFFECTIVE_MONTH;
    @Column(name = "allow_retroactive_generation", nullable = false)
    private boolean allowRetroactiveGeneration;
    @Column(name = "automatic_annual_generation", nullable = false)
    private boolean automaticAnnualGeneration;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

