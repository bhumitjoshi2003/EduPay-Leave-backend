package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.RazorpayKeysRequest;
import com.indraacademy.ias_management.dto.SchoolOnboardRequest;
import com.indraacademy.ias_management.dto.SchoolSettingsResponse;
import com.indraacademy.ias_management.dto.SchoolSettingsUpdateRequest;
import com.indraacademy.ias_management.dto.SuperAdminDashboardDto;
import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.entity.SubscriptionPlan;
import com.indraacademy.ias_management.service.SchoolService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.List;
import java.util.Map;

@RestController
public class SchoolController {

    private static final Logger log = LoggerFactory.getLogger(SchoolController.class);

    @Autowired private SchoolService schoolService;

    // ─── SUPER_ADMIN endpoints ────────────────────────────────────────────────

    /**
     * POST /api/super-admin/schools
     * Onboard a new school and create its first ADMIN user.
     */
    @PostMapping("/api/super-admin/schools")
    @PreAuthorize("hasRole('" + Role.SUPER_ADMIN + "')")
    public ResponseEntity<?> onboardSchool(@RequestBody SchoolOnboardRequest req,
                                           HttpServletRequest request) {
        log.info("POST /api/super-admin/schools — onboarding school '{}'", req.getName());
        try {
            SchoolSettingsResponse created = schoolService.onboardSchool(req, request);
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * GET /api/super-admin/schools
     * List all schools.
     */
    @GetMapping("/api/super-admin/schools")
    @PreAuthorize("hasRole('" + Role.SUPER_ADMIN + "')")
    public ResponseEntity<List<SchoolSettingsResponse>> listAllSchools() {
        log.info("GET /api/super-admin/schools");
        return ResponseEntity.ok(schoolService.listAllSchools());
    }

    /**
     * PATCH /api/super-admin/schools/{schoolId}/subscription
     * Update subscription plan, maxStudents, expiryDate, or active flag for a specific school.
     */
    @PatchMapping("/api/super-admin/schools/{schoolId}/subscription")
    @PreAuthorize("hasRole('" + Role.SUPER_ADMIN + "')")
    public ResponseEntity<?> updateSubscription(@PathVariable Long schoolId,
                                                @RequestParam(required = false) SubscriptionPlan plan,
                                                @RequestParam(required = false) Integer maxStudents,
                                                @RequestParam(required = false) String expiryDate,
                                                @RequestParam(required = false) Boolean active,
                                                HttpServletRequest request) {
        log.info("PATCH /api/super-admin/schools/{}/subscription", schoolId);
        try {
            LocalDate parsedExpiry = expiryDate != null ? LocalDate.parse(expiryDate) : null;
            SchoolSettingsResponse updated = schoolService.updateSubscription(schoolId, plan, maxStudents, parsedExpiry, active, request);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException | java.util.NoSuchElementException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    // ─── ADMIN / school-scoped endpoints ─────────────────────────────────────

    /**
     * GET /api/school/settings
     * Returns the calling school's settings.
     */
    @GetMapping("/api/school/settings")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "')")
    public ResponseEntity<?> getSettings() {
        log.info("GET /api/school/settings");
        try {
            return ResponseEntity.ok(schoolService.getSettings());
        } catch (java.util.NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * PUT /api/school/settings
     * Update editable school fields (name, contact info, logo, etc.).
     * Subscription fields are managed via the SUPER_ADMIN endpoint.
     */
    @PutMapping("/api/school/settings")
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    public ResponseEntity<?> updateSettings(@RequestBody SchoolSettingsUpdateRequest req,
                                            HttpServletRequest request) {
        log.info("PUT /api/school/settings");
        try {
            return ResponseEntity.ok(schoolService.updateSettings(req, request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (java.util.NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * PUT /api/school/razorpay
     * Update the school's Razorpay key ID and secret.
     */
    @PutMapping("/api/school/razorpay")
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    public ResponseEntity<?> updateRazorpayKeys(@RequestBody RazorpayKeysRequest req,
                                                HttpServletRequest request) {
        log.info("PUT /api/school/razorpay");
        try {
            schoolService.updateRazorpayKeys(req, request);
            return ResponseEntity.ok(Map.of("message", "Razorpay keys updated successfully."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (java.util.NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * GET /api/school/classes
     * Returns ordered active class names for the current school (for dropdowns).
     */
    @GetMapping("/api/school/classes")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "', 'TEACHER', 'STUDENT', 'SUB_ADMIN')")
    public ResponseEntity<List<String>> getClassNames() {
        log.info("GET /api/school/classes");
        return ResponseEntity.ok(schoolService.getClassNames());
    }

    /**
     * GET /api/school/classes/manage
     * Returns full SchoolClass records (id + name + order) for the management UI.
     */
    @GetMapping("/api/school/classes/manage")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUB_ADMIN + "')")
    public ResponseEntity<List<SchoolClass>> getManagedClasses() {
        return ResponseEntity.ok(schoolService.getManagedClasses());
    }

    /**
     * POST /api/school/classes
     * Adds a new class to the current school's list.
     */
    @PostMapping("/api/school/classes")
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    public ResponseEntity<?> addClass(@RequestBody java.util.Map<String, String> body) {
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Class name is required."));
        }
        try {
            SchoolClass created = schoolService.addClass(name);
            return ResponseEntity.ok(created);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * DELETE /api/school/classes/{id}
     * Soft-deletes a class from the current school's list.
     */
    @DeleteMapping("/api/school/classes/{id}")
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    public ResponseEntity<?> deleteClass(@PathVariable Long id) {
        try {
            schoolService.deleteClass(id);
            return ResponseEntity.noContent().build();
        } catch (java.util.NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * PATCH /api/school/classes/reorder
     * Accepts an ordered list of class IDs and updates displayOrder accordingly.
     */
    @PatchMapping("/api/school/classes/reorder")
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    public ResponseEntity<?> reorderClasses(@RequestBody List<Long> orderedIds) {
        try {
            schoolService.reorderClasses(orderedIds);
            return ResponseEntity.ok(Map.of("message", "Reordered successfully."));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * GET /api/super-admin/dashboard
     * Platform-wide stats: total schools, students, teachers, revenue this month.
     */
    @GetMapping("/api/super-admin/dashboard")
    @PreAuthorize("hasRole('" + Role.SUPER_ADMIN + "')")
    public ResponseEntity<SuperAdminDashboardDto> getSuperAdminDashboard() {
        log.info("GET /api/super-admin/dashboard");
        return ResponseEntity.ok(schoolService.getSuperAdminDashboard());
    }
}
