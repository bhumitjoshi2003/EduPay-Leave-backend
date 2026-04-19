package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.entity.Admin;
import com.indraacademy.ias_management.service.AdminService;
import com.indraacademy.ias_management.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/admins")
@CrossOrigin(origins = "http://localhost:4200")
@PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "')")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    @Autowired private AdminService adminService;
    @Autowired private AuthService authService;

    @PostMapping
    @PreAuthorize("hasRole('" + Role.SUPER_ADMIN + "')")
    public ResponseEntity<Admin> registerNewAdmin(@RequestBody Admin admin, HttpServletRequest request) {
        log.info("Request from Super Admin to create a new admin with email: {}", admin.getEmail());
        try {
            Admin savedAdmin = adminService.createAdmin(admin, request);
            log.info("Successfully created new Admin with ID: {}", savedAdmin.getAdminId());
            return ResponseEntity.ok(savedAdmin);
        } catch (IllegalArgumentException e) {
            log.error("Validation error during admin creation: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Unexpected error during admin creation", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/{adminId}")
    public ResponseEntity<Admin> getAdmin(@PathVariable String adminId) {
        log.info("Request to get Admin with ID: {}", adminId);
        Optional<Admin> admin = adminService.getAdminById(adminId);

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
    public ResponseEntity<Admin> updateAdmin(@PathVariable String adminId, @RequestBody Admin admin, HttpServletRequest request) {
        log.info("Request to update Admin with ID: {}", adminId);
        try {
            Admin updatedAdmin = adminService.updateAdmin(adminId, admin, request);
            log.info("Successfully updated Admin with ID: {}", adminId);
            return ResponseEntity.ok(updatedAdmin);
        } catch (NoSuchElementException e) {
            log.error("Admin with ID {} not found for update.", adminId);
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            log.error("Invalid data for Admin update (ID: {}): {}", adminId, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @DeleteMapping("/{adminId}")
    @PreAuthorize("hasRole('" + Role.SUPER_ADMIN + "')")
    public ResponseEntity<Void> deleteAdmin(@PathVariable String adminId, HttpServletRequest request) {
        log.warn("Request to delete Admin with ID: {}", adminId);
        try {
            adminService.deleteAdmin(adminId, request);
            log.info("Successfully deleted Admin with ID: {}", adminId);
            return ResponseEntity.noContent().build();
        } catch (NoSuchElementException e) {
            log.error("Admin with ID {} not found for deletion.", adminId);
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{adminId}/photo")
    public ResponseEntity<?> uploadAdminPhoto(@PathVariable String adminId,
                                              @RequestParam("file") MultipartFile file) {
        String currentUserId = authService.getUserId();
        String currentRole   = authService.getRole();

        // ADMIN can only upload their own photo; SUPER_ADMIN can upload for any admin
        if (Role.ADMIN.equals(currentRole) && !adminId.equals(currentUserId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Admins can only upload their own photo.");
        }

        log.info("Photo upload for admin {} by {} ({})", adminId, currentUserId, currentRole);

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("Uploaded file is empty.");
        }

        try {
            String photoUrl = adminService.uploadPhoto(adminId, file);
            return ResponseEntity.ok(Map.of("photoUrl", photoUrl));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (RuntimeException e) {
            log.error("Photo upload failed for admin {}", adminId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to upload photo.");
        }
    }
}