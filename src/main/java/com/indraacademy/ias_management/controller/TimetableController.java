package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.entity.TimetableEntry;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.TimetableService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/timetable")
public class TimetableController {

    private static final Logger log = LoggerFactory.getLogger(TimetableController.class);

    @Autowired private TimetableService timetableService;
    @Autowired private AuthService authService;

    /**
     * GET /api/timetable/class/{className}
     * All authenticated users may view a class timetable.
     */
    @GetMapping("/class/{className}")
    public ResponseEntity<List<TimetableEntry>> getByClass(@PathVariable String className) {
        log.info("GET timetable for class: {}", className);
        return ResponseEntity.ok(timetableService.getByClass(className));
    }

    /**
     * GET /api/timetable/teacher/{teacherId}
     * TEACHER: own schedule only. ADMIN / SUPER_ADMIN: any teacher.
     */
    @GetMapping("/teacher/{teacherId}")
    public ResponseEntity<?> getByTeacher(@PathVariable String teacherId) {
        String currentRole = authService.getRole();

        if (Role.TEACHER.equals(currentRole)) {
            String currentUserId = authService.getUserId();
            if (!teacherId.equals(currentUserId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("Teachers can only view their own schedule.");
            }
        } else if (!Role.ADMIN.equals(currentRole) && !Role.SUPER_ADMIN.equals(currentRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied.");
        }

        log.info("GET timetable for teacher: {}", teacherId);
        return ResponseEntity.ok(timetableService.getByTeacher(teacherId));
    }

    /**
     * POST /api/timetable
     * ADMIN / SUPER_ADMIN only.
     */
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "')")
    @PostMapping
    public ResponseEntity<?> create(@RequestBody TimetableEntry entry, HttpServletRequest request) {
        log.info("POST timetable: class={}, day={}, period={}", entry.getClassName(), entry.getDay(), entry.getPeriodNumber());
        TimetableEntry saved = timetableService.create(entry, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /**
     * PUT /api/timetable/{id}
     * ADMIN / SUPER_ADMIN only.
     */
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "')")
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody TimetableEntry entry, HttpServletRequest request) {
        log.info("PUT timetable/{}", id);
        TimetableEntry saved = timetableService.update(id, entry, request);
        return ResponseEntity.ok(saved);
    }

    /**
     * DELETE /api/timetable/{id}
     * ADMIN / SUPER_ADMIN only.
     */
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "')")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, HttpServletRequest request) {
        log.warn("DELETE timetable/{}", id);
        timetableService.delete(id, request);
        return ResponseEntity.noContent().build();
    }
}
