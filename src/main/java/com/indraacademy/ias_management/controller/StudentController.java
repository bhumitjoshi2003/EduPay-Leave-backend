package com.indraacademy.ias_management.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.BulkImportResultDTO;
import com.indraacademy.ias_management.dto.PromotionDecisionRequest;
import com.indraacademy.ias_management.dto.PromotionPreviewDTO;
import com.indraacademy.ias_management.dto.PromotionResultDTO;
import com.indraacademy.ias_management.dto.StudentLeaveDTO;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.UserRepository;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.StudentBulkImportService;
import com.indraacademy.ias_management.service.StudentPromotionService;
import com.indraacademy.ias_management.service.StudentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/students")
@CrossOrigin(origins = "http://localhost:4200")
public class StudentController {

    private static final Logger log = LoggerFactory.getLogger(StudentController.class);

    @Autowired private StudentService studentService;
    @Autowired private StudentBulkImportService studentBulkImportService;
    @Autowired private StudentPromotionService studentPromotionService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private StudentRepository studentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AuthService authService;

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PostMapping
    public ResponseEntity<?> registerStudent(@RequestBody Student newStudent, HttpServletRequest request) {
        log.info("Request to register new student: {}", newStudent.getStudentId());
        try {
            Student savedStudent = studentService.addStudent(newStudent, request);
            log.info("Student registered successfully with ID: {}", savedStudent.getStudentId());
            return new ResponseEntity<>(savedStudent, HttpStatus.CREATED);
        } catch (IllegalArgumentException e) {
            log.warn("Student registration failed (Conflict): {}", e.getMessage());
            return new ResponseEntity<>(e.getMessage(), HttpStatus.CONFLICT);
        } catch (Exception e) {
            log.error("Unexpected error during student registration.", e);
            return new ResponseEntity<>("Failed to register student.", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/{studentId}")
    public ResponseEntity<Student> getStudent(@PathVariable String studentId) {
        String role = authService.getRole();

        final String resolvedStudentId;

        if(Role.STUDENT.equals(role)){
            resolvedStudentId = authService.getUserId();
            log.info("Student accessing their own record with ID: {}", resolvedStudentId);
        } else {
            resolvedStudentId = studentId;
            log.info("Admin/Teacher accessing student record with ID: {}", resolvedStudentId);
        }

        Optional<Student> student = studentService.getStudent(resolvedStudentId);
        return student.map(ResponseEntity::ok)
                .orElseGet(() -> {
                    // Use the final variable in the lambda
                    log.warn("Student with ID {} not found.", resolvedStudentId);
                    return ResponseEntity.notFound().build();
                });
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PutMapping("/{studentId}")
    public ResponseEntity<Student> updateStudent(@PathVariable String studentId, @RequestBody Map<String, Object> requestBody, HttpServletRequest request) {
        log.info("Request to update student details for ID: {}", studentId);
        Student updatedStudent = objectMapper.convertValue(requestBody.get("studentDetails"), Student.class);
        Integer effectiveFromMonth = (Integer) requestBody.get("effectiveFromMonth");

        if (updatedStudent == null) {
            log.warn("Update student failed: Missing studentDetails");
            return ResponseEntity.badRequest().build();
        }

        Student savedStudent = studentService.updateStudent(studentId, updatedStudent, effectiveFromMonth, request);
        log.info("Student updated successfully with ID: {}", studentId);
        return ResponseEntity.ok(savedStudent);
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @GetMapping("/new/class/{className}")
    public List<StudentLeaveDTO> getNewStudentsByClass(@PathVariable String className) {
        log.info("Request to get UPCOMING students for class: {}", className);
        return studentService.getUpcomingStudentsByClass(className).stream()
                .map(s -> new StudentLeaveDTO(s.getStudentId(), s.getName()))
                .collect(Collectors.toList());
    }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    @GetMapping("/active/class/{className}")
    public List<StudentLeaveDTO> findActiveStudentsByClass(@PathVariable String className) {
        log.info("Request to get ACTIVE students for class: {}", className);
        return studentService.getActiveStudentsByClass(className).stream()
                .map(s -> new StudentLeaveDTO(s.getStudentId(), s.getName()))
                .collect(Collectors.toList());
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @GetMapping("/inactive/class/{className}")
    public List<StudentLeaveDTO> getInactiveStudentsByClass(@PathVariable String className) {
        log.info("Request to get INACTIVE students for class: {}", className);
        return studentService.getInactiveStudentsByClass(className).stream()
                .map(s -> new StudentLeaveDTO(s.getStudentId(), s.getName()))
                .collect(Collectors.toList());
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PostMapping("/bulk")
    public ResponseEntity<?> bulkImportStudents(
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {
        log.info("Received bulk student import request, file size: {} bytes", file.getSize());
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("Uploaded file is empty.");
        }
        BulkImportResultDTO result = studentBulkImportService.bulkImport(file, request);
        log.info("Bulk import completed: {} total, {} successful, {} failed",
                result.getTotalRows(), result.getSuccessful(), result.getFailed());
        return ResponseEntity.ok(result);
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PostMapping("/{studentId}/photo")
    public ResponseEntity<?> uploadStudentPhoto(@PathVariable String studentId,
                                                @RequestParam("file") MultipartFile file) {
        String currentUserId = authService.getUserId();
        String currentRole   = authService.getRole();

        // STUDENT can only upload their own photo
        if (Role.TEACHER.equals(currentRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Teachers cannot upload student photos.");
        }
        if (Role.STUDENT.equals(currentRole) && !studentId.equals(currentUserId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Students can only upload their own photo.");
        }

        log.info("Photo upload for student {} by {} ({})", studentId, currentUserId, currentRole);

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("Uploaded file is empty.");
        }

        String photoUrl = studentService.uploadPhoto(studentId, file);
        return ResponseEntity.ok(Map.of("photoUrl", photoUrl));
    }

    // ─── Promotion endpoints ──────────────────────────────────────────────────

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "')")
    @GetMapping("/promotion/preview")
    public ResponseEntity<List<PromotionPreviewDTO>> getPromotionPreview() {
        log.info("Request for student promotion preview");
        return ResponseEntity.ok(studentPromotionService.getPromotionPreview());
    }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "')")
    @PostMapping("/promotion/execute")
    public ResponseEntity<PromotionResultDTO> executePromotion(
            @RequestBody PromotionDecisionRequest request,
            HttpServletRequest httpRequest) {
        int count = request.getDecisions() != null ? request.getDecisions().size() : 0;
        log.warn("Request to execute promotion for {} student decisions", count);
        return ResponseEntity.ok(studentPromotionService.executePromotion(request, httpRequest));
    }

    @GetMapping("/bulk/template")
    public ResponseEntity<byte[]> downloadBulkImportTemplate() {
        log.info("Request to download student bulk import CSV template");
        String csvContent = String.join(",", StudentBulkImportService.TEMPLATE_HEADERS) + "\r\n";
        byte[] bytes = csvContent.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"student_import_template.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(bytes);
    }

}