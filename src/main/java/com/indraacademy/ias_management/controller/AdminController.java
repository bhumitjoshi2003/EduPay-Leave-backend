package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.entity.Admin;
import com.indraacademy.ias_management.service.AdminService;
import com.indraacademy.ias_management.service.ObjectStorageService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.List;

@RestController
@RequestMapping("/api/admins")
@PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "')")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    @Autowired private AdminService adminService;
    @Autowired private ObjectStorageService objectStorageService;

    @PostMapping
    @PreAuthorize("hasRole('" + Role.SUPER_ADMIN + "')")
    public ResponseEntity<Admin> registerNewAdmin(@Valid @RequestBody Admin admin, HttpServletRequest request) {
        log.info("Request from Super Admin to create a new admin with email: {}", admin.getEmail());
        Admin savedAdmin = adminService.createAdmin(admin, request);
        log.info("Successfully created new Admin with ID: {}", savedAdmin.getAdminId());
        return ResponseEntity.ok(savedAdmin);
    }

    @GetMapping("/{adminId}")
    public ResponseEntity<Admin> getAdmin(@PathVariable String adminId) {
        log.info("Request to get Admin with ID: {}", adminId);
        Optional<Admin> admin = adminService.getAdminById(adminId);
        admin.ifPresent(a -> a.setPhotoUrl(objectStorageService.resolveDisplayUrl(a.getPhotoUrl())));

        return admin.map(ResponseEntity::ok)
                .orElseGet(() -> {
                    log.warn("Admin with ID {} not found.", adminId);
                    return ResponseEntity.notFound().build();
                });
    }

    @GetMapping
    public ResponseEntity<List<Admin>> getAllAdmins() {
        log.info("Request to get all Admins.");
        List<Admin> admins = adminService.getAllAdmins();
        return ResponseEntity.ok(admins);
    }

    @PutMapping("/{adminId}")
    public ResponseEntity<Admin> updateAdmin(@PathVariable String adminId, @Valid @RequestBody Admin admin, HttpServletRequest request) {
        log.info("Request to update Admin with ID: {}", adminId);
        Admin updatedAdmin = adminService.updateAdmin(adminId, admin, request);
        log.info("Successfully updated Admin with ID: {}", adminId);
        return ResponseEntity.ok(updatedAdmin);
    }

    @DeleteMapping("/{adminId}")
    @PreAuthorize("hasRole('" + Role.SUPER_ADMIN + "')")
    public ResponseEntity<Void> deleteAdmin(@PathVariable String adminId, HttpServletRequest request) {
        log.warn("Request to delete Admin with ID: {}", adminId);
        adminService.deleteAdmin(adminId, request);
        log.info("Successfully deleted Admin with ID: {}", adminId);
        return ResponseEntity.noContent().build();
    }

}