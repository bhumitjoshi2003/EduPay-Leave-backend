package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.TeacherClassGrantDtos;
import com.indraacademy.ias_management.entity.TeacherClassGrant;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.TeacherClassGrantService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Admin-managed grants letting a teacher self-serve timetable periods for a class/section they
 * don't yet have any other connection to — see TeacherClassGrantService and
 * TimetableService#authorizeTeacherWrite.
 */
@RestController
@RequestMapping("/api/teacher-class-grants")
@PreAuthorize("isAuthenticated()")
public class TeacherClassGrantController {

    private static final Logger log = LoggerFactory.getLogger(TeacherClassGrantController.class);

    @Autowired private TeacherClassGrantService grantService;
    @Autowired private AuthService authService;

    /**
     * GET /api/teacher-class-grants?teacherId={id}
     * ADMIN / SUPER_ADMIN: any teacher (teacherId required). TEACHER: always their own
     * grants, regardless of the query param — same read-your-own pattern as
     * TimetableController#getByTeacher.
     */
    @GetMapping
    public ResponseEntity<?> getForTeacher(@RequestParam(required = false) String teacherId) {
        String role = authService.getRole();
        if (!Role.TEACHER.equals(role) && !Role.ADMIN.equals(role) && !Role.SUPER_ADMIN.equals(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied.");
        }
        String effectiveTeacherId = Role.TEACHER.equals(role) ? authService.getUserId() : teacherId;
        if (effectiveTeacherId == null || effectiveTeacherId.isBlank()) {
            return ResponseEntity.badRequest().body("teacherId is required.");
        }
        return ResponseEntity.ok(grantService.getForTeacher(effectiveTeacherId));
    }

    /**
     * POST /api/teacher-class-grants
     * ADMIN / SUPER_ADMIN only.
     */
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "')")
    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody TeacherClassGrantDtos.CreateRequest body, HttpServletRequest request) {
        log.info("POST teacher-class-grants: teacherId={}, class={}, sectionId={}", body.teacherId(), body.className(), body.sectionId());
        try {
            TeacherClassGrant saved = grantService.create(body.teacherId(), body.className(), body.sectionId(), request);
            return ResponseEntity.status(HttpStatus.CREATED).body(saved);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * DELETE /api/teacher-class-grants/{id}
     * ADMIN / SUPER_ADMIN only.
     */
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "')")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, HttpServletRequest request) {
        log.warn("DELETE teacher-class-grants/{}", id);
        grantService.delete(id, request);
        return ResponseEntity.noContent().build();
    }
}
