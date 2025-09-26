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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/teachers")
@CrossOrigin(origins = "http://localhost:4200")
@PreAuthorize("hasAnyRole('" + Role.ADMIN + "')")
public class TeacherController {

    private static final Logger log = LoggerFactory.getLogger(TeacherController.class);

    @Autowired private TeacherService teacherService;
    @Autowired private UserDetailsServiceImpl userDetailsService;

    @PostMapping
    public ResponseEntity<?> registerTeacher(@RequestBody Teacher newTeacher) {
        log.info("Request to register new teacher: {}", newTeacher.getTeacherId());
        try {
            Teacher savedTeacher = teacherService.addTeacher(newTeacher);
            log.info("Teacher registered successfully with ID: {}", savedTeacher.getTeacherId());
            return new ResponseEntity<>(savedTeacher, HttpStatus.CREATED);
        } catch (IllegalArgumentException e) {
            log.warn("Teacher registration failed (Conflict): {}", e.getMessage());
            return new ResponseEntity<>(e.getMessage(), HttpStatus.CONFLICT); // 409 Conflict
        } catch (Exception e) {
            log.error("Unexpected error during teacher registration.", e);
            return new ResponseEntity<>("Failed to register teacher.", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PreAuthorize("hasAnyRole('" + Role.TEACHER +  "', '" + Role.ADMIN + "')")
    @GetMapping("/{teacherId}")
    public ResponseEntity<Teacher> getTeacher(@PathVariable String teacherId) {
        final String finalTeacherId = teacherId;
        log.info("Request to get teacher with ID: {}", finalTeacherId);
        try {
            Optional<Teacher> teacher = teacherService.getTeacher(finalTeacherId);
            return teacher.map(ResponseEntity::ok)
                    .orElseGet(() -> {
                        log.warn("Teacher with ID {} not found.", finalTeacherId);
                        return ResponseEntity.notFound().build();
                    });
        } catch (Exception e) {
            log.error("Unexpected error fetching teacher ID: {}.", finalTeacherId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping
    public ResponseEntity<List<Teacher>> getAllTeachers() {
        log.info("Request to get all teachers.");
        try {
            List<Teacher> teachers = teacherService.getAllTeachers();
            if (teachers.isEmpty()) {
                log.info("No teachers found.");
                return ResponseEntity.noContent().build();
            }
            log.info("Successfully returned {} teachers.", teachers.size());
            return ResponseEntity.ok(teachers);
        } catch (Exception e) {
            log.error("Unexpected error fetching all teachers.", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PutMapping("/{teacherId}")
    public ResponseEntity<Teacher> updateTeacher(@PathVariable String teacherId, @RequestBody Teacher updatedTeacher) {
        log.info("Request to update teacher ID: {}", teacherId);
        try {
            Optional<Teacher> existingTeacherOptional = teacherService.getTeacher(teacherId);

            if (existingTeacherOptional.isPresent()) {
                Teacher existingTeacher = existingTeacherOptional.get();

                // Check if email has changed
                boolean emailChanged = !existingTeacher.getEmail().equals(updatedTeacher.getEmail());

                if (emailChanged) {
                    log.info("Teacher {} email updated from {} to {}. Updating associated User record.",
                            teacherId, existingTeacher.getEmail(), updatedTeacher.getEmail());

                    Optional<User> userOptional = userDetailsService.findUserByUserId(teacherId);
                    userOptional.ifPresent(user -> {
                        user.setEmail(updatedTeacher.getEmail());
                        userDetailsService.save(user);
                        log.info("Associated User record updated successfully for teacher ID: {}", teacherId);
                    });
                }

                Teacher savedTeacher = teacherService.updateTeacher(updatedTeacher);
                log.info("Teacher details updated successfully for ID: {}", teacherId);
                return ResponseEntity.ok(savedTeacher);
            } else {
                log.warn("Teacher with ID {} not found for update.", teacherId);
                return ResponseEntity.notFound().build();
            }
        } catch (NoSuchElementException e) {
            log.error("Update failed: Teacher with ID {} not found (should be caught by isPresent check, but safe to include).", teacherId, e);
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            log.error("Update failed for teacher ID {}: Invalid data provided. {}", teacherId, e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Unexpected error during teacher update for ID {}.", teacherId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}