package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.service.TeacherService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/teachers")
@CrossOrigin(origins = "http://localhost:4200")
public class TeacherController {

    @Autowired
    private TeacherService teacherService;

    @GetMapping("/{teacherId}")
    public ResponseEntity<Teacher> getTeacher(@PathVariable String teacherId) {
        Optional<Teacher> teacher = teacherService.getTeacher(teacherId);
        return teacher.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<Teacher>> getAllTeachers() {
        List<Teacher> teachers = teacherService.getAllTeachers();
        if (teachers.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(teachers);
    }

    @PutMapping("/{teacherId}")
    public ResponseEntity<Teacher> updateTeacher(@PathVariable String teacherId, @RequestBody Teacher updatedTeacher) {
        Optional<Teacher> existingTeacher = teacherService.getTeacher(teacherId);
        if (existingTeacher.isPresent()) {
            updatedTeacher.setTeacherId(teacherId); // Ensure ID is consistent
            Teacher savedTeacher = teacherService.updateTeacher(updatedTeacher);
            return ResponseEntity.ok(savedTeacher);
        } else {
            return ResponseEntity.notFound().build();
        }
    }
}