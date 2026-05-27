package com.indraacademy.ias_management.dto;

import java.util.List;

public class RolePermissionDTO {
    private String role;
    private List<String> permissionKeys;

    public RolePermissionDTO() {}

    public RolePermissionDTO(String role, List<String> permissionKeys) {
        this.role = role;
        this.permissionKeys = permissionKeys;
    }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public List<String> getPermissionKeys() { return permissionKeys; }
    public void setPermissionKeys(List<String> permissionKeys) { this.permissionKeys = permissionKeys; }
}
