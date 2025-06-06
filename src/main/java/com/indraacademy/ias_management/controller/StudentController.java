package com.indraacademy.ias_management.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.ChangePasswordRequest;
import com.indraacademy.ias_management.dto.StudentLeaveDTO;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.UserRepository;
import com.indraacademy.ias_management.service.StudentService;
import com.indraacademy.ias_management.service.UserDetailsServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<Student> getStudent(@PathVariable String studentId) {
        Optional<Student> student = studentService.getStudent(studentId);
        return student.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

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


    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody ChangePasswordRequest request) {
        Optional<User> userOptional = userRepository.findByUserId(request.getUserId());
        if (userOptional.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("User not found");
        }

        User user = userOptional.get();
        if (request.getOldPassword() != null && !request.getOldPassword().isEmpty()) {
            if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid old password");
            }
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        return ResponseEntity.ok("Password changed successfully");
    }


    @GetMapping("/new/class/{className}")
    public List<StudentLeaveDTO> getNewStudentsByClass(@PathVariable String className) {
        List<Student> students = studentRepository.findByClassNameAndJoiningDateGreaterThan(className, LocalDate.now());
        return students.stream()
                .map(student -> new StudentLeaveDTO(student.getStudentId(), student.getName()))
                .collect(Collectors.toList());
    }

    @GetMapping("/class/{className}")
    public List<StudentLeaveDTO> findByClassNameAndJoiningDate(@PathVariable String className) {
        List<Student> students = studentRepository.findByClassNameAndJoiningDateLessThanEqual(className, LocalDate.now());
        return students.stream()
                .map(student -> new StudentLeaveDTO(student.getStudentId(), student.getName()))
                .collect(Collectors.toList());
    }
}

