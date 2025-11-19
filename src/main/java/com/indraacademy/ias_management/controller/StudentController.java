package com.indraacademy.ias_management.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.StudentLeaveDTO;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.UserRepository;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.StudentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/students")
@CrossOrigin(origins = "http://localhost:4200")
public class StudentController {

    private static final Logger log = LoggerFactory.getLogger(StudentController.class);

    @Autowired private StudentService studentService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private StudentRepository studentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AuthService authService;

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PostMapping
    public ResponseEntity<?> registerStudent(@RequestBody Student newStudent) {
        log.info("Request to register new student: {}", newStudent.getStudentId());
        try {
            Student savedStudent = studentService.addStudent(newStudent);
            log.info("Student registered successfully with ID: {}", savedStudent.getStudentId());
            return new ResponseEntity<>(savedStudent, HttpStatus.CREATED);
        } catch (IllegalArgumentException e) {
            log.warn("Student registration failed (Conflict): {}", e.getMessage());
            return new ResponseEntity<>(e.getMessage(), HttpStatus.CONFLICT); // 409 Conflict
        } catch (Exception e) {
            log.error("Unexpected error during student registration.", e);
            return new ResponseEntity<>("Failed to register student.", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/{studentId}")
    public ResponseEntity<Student> getStudent(@PathVariable String studentId, @RequestHeader(name = "Authorization") String authorizationHeader) {
        String role = authService.getRoleFromToken(authorizationHeader);

        final String resolvedStudentId;

        if(Role.STUDENT.equals(role)){
            resolvedStudentId = authService.getUserIdFromToken(authorizationHeader);
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
    public ResponseEntity<Student> updateStudent(@PathVariable String studentId, @RequestBody Map<String, Object> requestBody) {
        log.info("Request to update student details for ID: {}", studentId);
        try {
            Student updatedStudent = objectMapper.convertValue(requestBody.get("studentDetails"), Student.class);
            Integer effectiveFromMonth = (Integer) requestBody.get("effectiveFromMonth");

            if (updatedStudent == null) {
                log.warn("Update student failed: Missing studentDetails");
                return ResponseEntity.badRequest().build();
            }

            Student savedStudent = studentService.updateStudent(studentId, updatedStudent, effectiveFromMonth);
            log.info("Student updated successfully with ID: {}", studentId);
            return ResponseEntity.ok(savedStudent);
        } catch (NoSuchElementException e) {
            log.error("Student with ID {} not found for update.", studentId);
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            log.error("Invalid data provided for updating student {}: {}", studentId, e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Unexpected error during student update for ID {}.", studentId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
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

}