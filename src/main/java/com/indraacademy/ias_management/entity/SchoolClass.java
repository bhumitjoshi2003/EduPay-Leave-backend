package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "school_class",
        uniqueConstraints = @UniqueConstraint(name = "uq_school_class_name", columnNames = {"school_id", "name"}))
public class SchoolClass {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "display_order")
    private Integer displayOrder;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "stream_eligible", nullable = false)
    private boolean streamEligible = false;

    public SchoolClass() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Integer getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public boolean isStreamEligible() { return streamEligible; }
    public void setStreamEligible(boolean streamEligible) { this.streamEligible = streamEligible; }
}
