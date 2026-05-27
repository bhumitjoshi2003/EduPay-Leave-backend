package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "permission")
public class Permission {

    @Id
    @Column(name = "permission_key", length = 60)
    private String permissionKey;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(length = 50)
    private String category;

    @Column(length = 255)
    private String description;

    public Permission() {}

    public Permission(String permissionKey, String displayName, String category, String description) {
        this.permissionKey = permissionKey;
        this.displayName = displayName;
        this.category = category;
        this.description = description;
    }

    public String getPermissionKey() { return permissionKey; }
    public void setPermissionKey(String permissionKey) { this.permissionKey = permissionKey; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
