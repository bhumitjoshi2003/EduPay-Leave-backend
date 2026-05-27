package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "role_permission",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_role_permission",
                columnNames = {"role", "permission_key", "school_id"}
        ))
public class RolePermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String role;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "permission_key", nullable = false)
    private Permission permission;

    @Column(name = "school_id")
    private Long schoolId;

    public RolePermission() {}

    public RolePermission(String role, Permission permission, Long schoolId) {
        this.role = role;
        this.permission = permission;
        this.schoolId = schoolId;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public Permission getPermission() { return permission; }
    public void setPermission(Permission permission) { this.permission = permission; }

    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }
}
