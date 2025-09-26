package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.entity.Admin;
import com.indraacademy.ias_management.service.AdminService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/admins")
@CrossOrigin(origins = "http://localhost:4200")
@PreAuthorize("hasRole('" + Role.ADMIN + "')")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    @Autowired
    private AdminService adminService;

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
    public ResponseEntity<Admin> updateAdmin(@PathVariable String adminId, @RequestBody Admin admin) {
        log.info("Request to update Admin with ID: {}", adminId);
        try {
            Admin updatedAdmin = adminService.updateAdmin(adminId, admin);
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
    public ResponseEntity<Void> deleteAdmin(@PathVariable String adminId) {
        log.warn("Request to delete Admin with ID: {}", adminId);
        try {
            adminService.deleteAdmin(adminId);
            log.info("Successfully deleted Admin with ID: {}", adminId);
            return ResponseEntity.noContent().build();
        } catch (NoSuchElementException e) {
            log.error("Admin with ID {} not found for deletion.", adminId);
            return ResponseEntity.notFound().build();
        }
    }
}