package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.PermissionDTO;
import com.indraacademy.ias_management.dto.RolePermissionDTO;
import com.indraacademy.ias_management.service.PermissionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/permissions")
public class PermissionController {

    @Autowired
    private PermissionService permissionService;

    @GetMapping
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    public ResponseEntity<List<PermissionDTO>> getAllPermissions() {
        return ResponseEntity.ok(permissionService.getAllPermissions());
    }

    @GetMapping("/matrix")
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    public ResponseEntity<List<RolePermissionDTO>> getRolePermissionMatrix() {
        return ResponseEntity.ok(permissionService.getRolePermissionMatrix());
    }
}
