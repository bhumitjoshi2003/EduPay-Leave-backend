package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.TimetableBulkImportDtos;
import com.indraacademy.ias_management.dto.TimetableDtos;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.TimetableEntry;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.ParentPortalService;
import com.indraacademy.ias_management.service.TimetableBulkImportService;
import com.indraacademy.ias_management.service.TimetableService;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/timetable")
@PreAuthorize("isAuthenticated()")
public class TimetableController {

    private static final Logger log = LoggerFactory.getLogger(TimetableController.class);

    @Autowired private TimetableService timetableService;
    @Autowired private TimetableBulkImportService timetableBulkImportService;
    @Autowired private AuthService authService;
    @Autowired private ParentPortalService parentPortalService;
    @Autowired private StudentRepository studentRepository;
    @Autowired private SecurityUtil securityUtil;

    /**
     * GET /api/timetable/class/{className}?sectionId={id}
     * Returns timetable for the class. Pass sectionId to filter to a specific section.
     * Without sectionId, all entries for the class are returned.
     */
    @GetMapping("/class/{className}")
    public ResponseEntity<List<TimetableEntry>> getByClass(
            @PathVariable String className,
            @RequestParam(required = false) Long sectionId,
            @RequestParam(required = false) String studentId) {
        if (Role.PARENT.equals(authService.getRole())) {
            if (studentId == null || studentId.isBlank()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            parentPortalService.assertChildAccess(studentId, ParentPortalService.ChildPermission.TIMETABLE);
            Student child = studentRepository.findByStudentIdAndSchoolId(studentId, securityUtil.getSchoolId())
                    .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                            HttpStatus.NOT_FOUND, "Linked student not found"));
            if (!className.equals(child.getClassName())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            sectionId = child.getSectionId();
        }
        log.info("GET timetable for class: {}, sectionId: {}", className, sectionId);
        return ResponseEntity.ok(timetableService.getByClass(className, sectionId));
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
    public ResponseEntity<?> create(@Valid @RequestBody TimetableEntry entry, HttpServletRequest request) {
        log.info("POST timetable: class={}, day={}, period={}", entry.getClassName(), entry.getDay(), entry.getPeriodNumber());
        try {
            TimetableEntry saved = timetableService.create(entry, request);
            return ResponseEntity.status(HttpStatus.CREATED).body(saved);
        } catch (DataIntegrityViolationException e) {
            // Surfaces the specific reason (slot conflict, group mismatch, teacher double-booking,
            // etc.) rather than letting GlobalExceptionHandler's generic 409 message swallow it.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        }
    }

    /**
     * PUT /api/timetable/{id}
     * ADMIN / SUPER_ADMIN only.
     */
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "')")
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody TimetableEntry entry, HttpServletRequest request) {
        log.info("PUT timetable/{}", id);
        try {
            TimetableEntry saved = timetableService.update(id, entry, request);
            return ResponseEntity.ok(saved);
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        }
    }

    /**
     * POST /api/timetable/{id}/simultaneous
     * ADMIN / SUPER_ADMIN only. Adds a second subject to the same slot as entry {id} — the
     * "+ Simultaneous" action. Class/section/day/period/time are inherited server-side from the
     * existing entry and the simultaneousGroup tag is generated/reused automatically; the admin
     * only ever supplies the new subject and teacher. See TimetableService#addSimultaneous.
     */
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "')")
    @PostMapping("/{id}/simultaneous")
    public ResponseEntity<?> addSimultaneous(@PathVariable Long id,
            @Valid @RequestBody TimetableDtos.AddSimultaneousRequest body, HttpServletRequest request) {
        log.info("POST timetable/{}/simultaneous", id);
        try {
            TimetableEntry saved = timetableService.addSimultaneous(id, body.subjectName(), body.teacherId(), request);
            return ResponseEntity.status(HttpStatus.CREATED).body(saved);
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        }
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

    /**
     * POST /api/timetable/bulk
     * ADMIN / SUPER_ADMIN only. Uploads a CSV of period slots — see
     * {@link TimetableBulkImportService} for the expected column layout.
     */
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "')")
    @PostMapping("/bulk")
    public ResponseEntity<?> bulkImport(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        log.info("Received bulk timetable import request, file size: {} bytes", file.getSize());
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("Uploaded file is empty.");
        }
        TimetableBulkImportDtos.Result result = timetableBulkImportService.bulkImport(file, request);
        log.info("Timetable bulk import completed: {} total, {} successful, {} failed",
                result.totalRows(), result.successful(), result.failed());
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/timetable/bulk/template
     * ADMIN / SUPER_ADMIN only.
     */
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "')")
    @GetMapping("/bulk/template")
    public ResponseEntity<byte[]> downloadBulkImportTemplate() {
        log.info("Request to download timetable bulk import CSV template");
        String csvContent = String.join(",", TimetableBulkImportService.TEMPLATE_HEADERS) + "\r\n";
        byte[] bytes = csvContent.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"timetable_import_template.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(bytes);
    }
}
