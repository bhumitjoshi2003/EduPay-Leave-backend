package com.indraacademy.ias_management.dto;

public class PermissionDTO {
    private String permissionKey;
    private String displayName;
    private String category;
    private String description;

    public PermissionDTO() {}

    public PermissionDTO(String permissionKey, String displayName, String category, String description) {
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
