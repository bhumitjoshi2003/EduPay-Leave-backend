package com.indraacademy.ias_management.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.StudentLeaveDTO;
import com.indraacademy.ias_management.entity.Student;
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
    @Autowired private StudentService studentService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private StudentRepository studentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AuthService authService;

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PostMapping
    public ResponseEntity<?> registerStudent(@RequestBody Student newStudent) {
        try {
            Student savedStudent = studentService.addStudent(newStudent);
            return new ResponseEntity<>(savedStudent, HttpStatus.CREATED);
        } catch (IllegalArgumentException e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.CONFLICT);
        }
    }

    @GetMapping("/{studentId}")
    public ResponseEntity<Student> getStudent(@PathVariable String studentId, @RequestHeader(name = "Authorization") String authorizationHeader) {
        String role = authService.getRoleFromToken(authorizationHeader);
        if(role.equals("STUDENT")){
            studentId = authService.getUserIdFromToken(authorizationHeader);
        }
        Optional<Student> student = studentService.getStudent(studentId);
        return student.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PutMapping("/{studentId}")
    public ResponseEntity<Student> updateStudent(@PathVariable String studentId, @RequestBody Map<String, Object> requestBody) {
        try {
            Student updatedStudent = objectMapper.convertValue(requestBody.get("studentDetails"), Student.class);
            Integer effectiveFromMonth = (Integer) requestBody.get("effectiveFromMonth");
            Student savedStudent = studentService.updateStudent(studentId, updatedStudent, effectiveFromMonth);
            return ResponseEntity.ok(savedStudent);
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @GetMapping("/new/class/{className}")
    public List<StudentLeaveDTO> getNewStudentsByClass(@PathVariable String className) {
        List<Student> students = studentRepository.findByClassNameAndJoiningDateGreaterThan(className, LocalDate.now());
        return students.stream()
                .map(student -> new StudentLeaveDTO(student.getStudentId(), student.getName()))
                .collect(Collectors.toList());
    }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    @GetMapping("/class/{className}")
    public List<StudentLeaveDTO> findByClassNameAndJoiningDate(@PathVariable String className) {
        List<Student> students = studentRepository.findByClassNameAndJoiningDateLessThanEqual(className, LocalDate.now());
        return students.stream()
                .map(student -> new StudentLeaveDTO(student.getStudentId(), student.getName()))
                .collect(Collectors.toList());
    }
}

