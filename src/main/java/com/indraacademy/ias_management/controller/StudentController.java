package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.service.StudentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/students")
@CrossOrigin(origins = "http://localhost:4200")
public class StudentController {

    @Autowired
    private StudentService studentService;

    @GetMapping("/{studentId}")
    public ResponseEntity<Student> getStudent(@PathVariable String studentId) {
        Optional<Student> student = studentService.getStudent(studentId);
        return student.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{studentId}")
    public ResponseEntity<Student> updateStudent(@PathVariable String studentId, @RequestBody Student updatedStudent) {
        Optional<Student> existingStudent = studentService.getStudent(studentId);
        if (existingStudent.isPresent()) {
            Student savedStudent = studentService.updateStudent(updatedStudent);
            return ResponseEntity.ok(savedStudent);
        } else {
            return ResponseEntity.notFound().build();
        }
    }
}

