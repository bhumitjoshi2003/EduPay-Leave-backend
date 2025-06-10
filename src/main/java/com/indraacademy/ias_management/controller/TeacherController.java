package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.service.TeacherService;
import com.indraacademy.ias_management.service.UserDetailsServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/teachers")
@CrossOrigin(origins = "http://localhost:4200")
@PreAuthorize("hasAnyRole('" + Role.ADMIN + "')")
public class TeacherController {

    @Autowired private TeacherService teacherService;
    @Autowired private UserDetailsServiceImpl userDetailsService;

    @PostMapping
    public ResponseEntity<?> registerTeacher(@RequestBody Teacher newTeacher) {
        try {
            Teacher savedTeacher = teacherService.addTeacher(newTeacher);
            return new ResponseEntity<>(savedTeacher, HttpStatus.CREATED);
        } catch (IllegalArgumentException e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.CONFLICT); // 409 Conflict
        }
    }

    @PreAuthorize("hasAnyRole('" + Role.TEACHER +  "', '" + Role.ADMIN + "')")
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
        Optional<Teacher> existingTeacherOptional = teacherService.getTeacher(teacherId);
        if (existingTeacherOptional.isPresent()) {
            Teacher existingTeacher = existingTeacherOptional.get();

            boolean emailChanged = !existingTeacher.getEmail().equals(updatedTeacher.getEmail());

            if (emailChanged) {
                Optional<User> userOptional = userDetailsService.findUserByUserId(teacherId);
                userOptional.ifPresent(user -> {
                    user.setEmail(updatedTeacher.getEmail());
                    userDetailsService.save(user);
                });
            }
            Teacher savedTeacher = teacherService.updateTeacher(updatedTeacher);
            return ResponseEntity.ok(savedTeacher);
        } else {
            return ResponseEntity.notFound().build();
        }
    }
}