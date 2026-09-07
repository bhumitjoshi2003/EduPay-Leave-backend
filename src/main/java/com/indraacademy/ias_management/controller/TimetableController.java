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
import com.indraacademy.ias_management.service.TimetableSessionCopyService;
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
    @Autowired private TimetableSessionCopyService timetableSessionCopyService;
    @Autowired private AuthService authService;
    @Autowired private ParentPortalService parentPortalService;
    @Autowired private StudentRepository studentRepository;
    @Autowired private SecurityUtil securityUtil;

    private boolean isAdmin(String role) {
        return Role.ADMIN.equals(role) || Role.SUPER_ADMIN.equals(role);
    }

    /**
     * GET /api/timetable/class/{className}?sectionId={id}&academicSessionId={id}
     * Without {@code academicSessionId}: operational read, resolves the school's CURRENT
     * session server-side (see TimetableService#getByClass) — unchanged contract for existing
     * teacher/student/parent/admin screens. With an explicit {@code academicSessionId}:
     * ADMIN/SUPER_ADMIN-only historical/future configuration view.
     */
    @GetMapping("/class/{className}")
    public ResponseEntity<?> getByClass(
            @PathVariable String className,
            @RequestParam(required = false) Long sectionId,
            @RequestParam(required = false) String studentId,
            @RequestParam(required = false) Long academicSessionId) {
        String role = authService.getRole();
        if (Role.PARENT.equals(role)) {
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
        if (academicSessionId != null) {
            if (!isAdmin(role)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("Only an admin may view a specific academic session's timetable.");
            }
            log.info("GET timetable for class: {}, sectionId: {}, academicSessionId: {}", className, sectionId, academicSessionId);
            return ResponseEntity.ok(timetableService.getByClassForSession(className, sectionId, academicSessionId));
        }
        log.info("GET timetable for class: {}, sectionId: {}", className, sectionId);
        return ResponseEntity.ok(timetableService.getByClass(className, sectionId));
    }

    /**
     * GET /api/timetable/teacher/{teacherId}?academicSessionId={id}
     * TEACHER: own current-session schedule only (no explicit session param honored). ADMIN /
     * SUPER_ADMIN: any teacher, current session by default or an explicit historical/future
     * session via {@code academicSessionId}.
     */
    @GetMapping("/teacher/{teacherId}")
    public ResponseEntity<?> getByTeacher(@PathVariable String teacherId,
            @RequestParam(required = false) Long academicSessionId) {
        String currentRole = authService.getRole();

        if (Role.TEACHER.equals(currentRole)) {
            String currentUserId = authService.getUserId();
            if (!teacherId.equals(currentUserId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("Teachers can only view their own schedule.");
            }
            log.info("GET timetable for teacher: {}", teacherId);
            return ResponseEntity.ok(timetableService.getByTeacher(teacherId));
        } else if (!isAdmin(currentRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied.");
        }

        if (academicSessionId != null) {
            log.info("GET timetable for teacher: {}, academicSessionId: {}", teacherId, academicSessionId);
            return ResponseEntity.ok(timetableService.getByTeacherForSession(teacherId, academicSessionId));
        }
        log.info("GET timetable for teacher: {}", teacherId);
        return ResponseEntity.ok(timetableService.getByTeacher(teacherId));
    }

    /**
     * POST /api/timetable
     * ADMIN / SUPER_ADMIN: any teacher, any class, in an explicit non-historical session they
     * own — {@code academicSessionId} is required and never inferred. TEACHER: may only add
     * themselves into a class/section they already teach or are the class-teacher of, in the
     * server-resolved CURRENT session — any {@code academicSessionId} they supply is ignored,
     * enforced server-side in TimetableService#create.
     */
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "', '" + Role.TEACHER + "')")
    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody TimetableDtos.TimetableEntryRequest req, HttpServletRequest request) {
        log.info("POST timetable: classId={}, day={}, period={}", req.classId(), req.day(), req.periodNumber());
        try {
            TimetableEntry saved = timetableService.create(req, authService.getRole(), authService.getUserId(), request);
            return ResponseEntity.status(HttpStatus.CREATED).body(saved);
        } catch (DataIntegrityViolationException e) {
            // Surfaces the specific reason (slot conflict, group mismatch, teacher double-booking,
            // session mismatch, etc.) rather than letting GlobalExceptionHandler's generic 409
            // message swallow it.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (java.util.NoSuchElementException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        }
    }

    /**
     * PUT /api/timetable/{id}
     * ADMIN / SUPER_ADMIN only. {@code req.academicSessionId} must match the entry's actual
     * session — an update can never move a row across sessions, and never silently targets
     * whichever session happens to be current.
     */
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "')")
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody TimetableDtos.TimetableEntryRequest req, HttpServletRequest request) {
        log.info("PUT timetable/{}", id);
        try {
            TimetableEntry saved = timetableService.update(id, req, request);
            return ResponseEntity.ok(saved);
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        } catch (java.util.NoSuchElementException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        }
    }

    /**
     * POST /api/timetable/{id}/simultaneous
     * ADMIN / SUPER_ADMIN: any teacher, {@code body.academicSessionId} must match entry {id}'s
     * actual session. TEACHER: always assigned to themselves regardless of {@code body.teacherId},
     * must already teach or be the class-teacher of entry {id}'s class/section in the current
     * session, and entry {id} must itself belong to the current session — enforced server-side in
     * TimetableService#addSimultaneous. Adds a second subject to the same slot as entry {id} —
     * the "+ Simultaneous" action. Class/section/day/period/time/session are inherited
     * server-side from the existing entry and the simultaneousGroup tag is generated/reused
     * automatically.
     */
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "', '" + Role.TEACHER + "')")
    @PostMapping("/{id}/simultaneous")
    public ResponseEntity<?> addSimultaneous(@PathVariable Long id,
            @Valid @RequestBody TimetableDtos.AddSimultaneousRequest body, HttpServletRequest request) {
        log.info("POST timetable/{}/simultaneous", id);
        try {
            TimetableEntry saved = timetableService.addSimultaneous(id, body.academicSessionId(), body.subjectName(),
                    body.teacherId(), authService.getRole(), authService.getUserId(), request);
            return ResponseEntity.status(HttpStatus.CREATED).body(saved);
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (java.util.NoSuchElementException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        }
    }

    /**
     * DELETE /api/timetable/{id}?academicSessionId={id}
     * ADMIN / SUPER_ADMIN only. {@code academicSessionId} must match the entry's actual session
     * — fail closed otherwise, exactly like update.
     */
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "')")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id,
            @RequestParam Long academicSessionId, HttpServletRequest request) {
        log.warn("DELETE timetable/{}, academicSessionId={}", id, academicSessionId);
        try {
            timetableService.delete(id, academicSessionId, request);
            return ResponseEntity.noContent().build();
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        } catch (java.util.NoSuchElementException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        }
    }

    /**
     * POST /api/timetable/bulk?academicSessionId={id}
     * ADMIN / SUPER_ADMIN only. Uploads a CSV of period slots into an explicit target session —
     * see {@link TimetableBulkImportService} for the expected column layout.
     */
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "')")
    @PostMapping("/bulk")
    public ResponseEntity<?> bulkImport(@RequestParam("file") MultipartFile file,
            @RequestParam Long academicSessionId, HttpServletRequest request) {
        log.info("Received bulk timetable import request, file size: {} bytes, academicSessionId: {}",
                file.getSize(), academicSessionId);
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("Uploaded file is empty.");
        }
        try {
            TimetableBulkImportDtos.Result result = timetableBulkImportService.bulkImport(file, academicSessionId, request);
            log.info("Timetable bulk import completed: sessionId={}, {} total, {} successful, {} failed",
                    academicSessionId, result.totalRows(), result.successful(), result.failed());
            return ResponseEntity.ok(result);
        } catch (java.util.NoSuchElementException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        }
    }

    /**
     * POST /api/timetable/copy-session
     * ADMIN / SUPER_ADMIN only. Explicit, one-shot copy of one session's timetable configuration
     * into another — never automatic, never a fallback. See TimetableSessionCopyService.
     */
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "')")
    @PostMapping("/copy-session")
    public ResponseEntity<?> copySession(@Valid @RequestBody TimetableDtos.CopySessionRequest req, HttpServletRequest request) {
        log.warn("POST timetable/copy-session: source={}, target={}", req.sourceAcademicSessionId(), req.targetAcademicSessionId());
        try {
            return ResponseEntity.ok(timetableSessionCopyService.copy(
                    req.sourceAcademicSessionId(), req.targetAcademicSessionId(), req.confirmCurrentTarget(), request));
        } catch (java.util.NoSuchElementException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        }
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
