package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "optional_subject_group")
@Data
public class OptionalSubjectGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_name", nullable = false)
    private String groupName;

    public OptionalSubjectGroup() {}

    public Long getId() { return id; }
    public String getGroupName() { return groupName; }

    public void setId(Long id) { this.id = id; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
}
