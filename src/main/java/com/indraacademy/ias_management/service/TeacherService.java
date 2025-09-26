package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.repository.TeacherRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
public class TeacherService {

    private static final Logger log = LoggerFactory.getLogger(TeacherService.class);

    @Autowired
    private TeacherRepository teacherRepository;

    public Optional<Teacher> getTeacher(String teacherId) {
        if (teacherId == null || teacherId.trim().isEmpty()) {
            log.warn("Attempted to get teacher with null/empty ID.");
            return Optional.empty();
        }
        log.info("Fetching teacher with ID: {}", teacherId);
        try {
            return teacherRepository.findById(teacherId);
        } catch (DataAccessException e) {
            log.error("Data access error fetching teacher with ID: {}", teacherId, e);
            throw new RuntimeException("Failed to retrieve teacher data.", e);
        }
    }

    public List<Teacher> getAllTeachers() {
        log.info("Fetching all teachers.");
        try {
            return teacherRepository.findAll();
        } catch (DataAccessException e) {
            log.error("Data access error fetching all teachers.", e);
            throw new RuntimeException("Failed to retrieve list of teachers.", e);
        }
    }

    public Teacher updateTeacher(Teacher teacher) {
        if (teacher == null || teacher.getTeacherId() == null || teacher.getTeacherId().trim().isEmpty()) {
            log.error("Attempted to update teacher with null or empty Teacher object/ID.");
            throw new IllegalArgumentException("Teacher object and ID must be provided for update.");
        }
        log.info("Updating teacher with ID: {}", teacher.getTeacherId());

        try {
            return teacherRepository.save(teacher);
        } catch (DataAccessException e) {
            log.error("Data access error updating teacher with ID: {}", teacher.getTeacherId(), e);
            throw new RuntimeException("Failed to update teacher due to a database issue.", e);
        }
    }

    public Teacher addTeacher(Teacher teacher) {
        if (teacher == null || teacher.getTeacherId() == null || teacher.getTeacherId().trim().isEmpty()) {
            log.error("Attempted to add teacher with null or empty Teacher object/ID.");
            throw new IllegalArgumentException("Teacher object and ID must be provided.");
        }
        log.info("Attempting to add new teacher with ID: {}", teacher.getTeacherId());

        try {
            Optional<Teacher> existingTeacher = teacherRepository.findById(teacher.getTeacherId());
            if (existingTeacher.isPresent()) {
                log.warn("Teacher with ID {} already exists.", teacher.getTeacherId());
                throw new IllegalArgumentException("Teacher with ID " + teacher.getTeacherId() + " already exists.");
            }
            Teacher savedTeacher = teacherRepository.save(teacher);
            log.info("Successfully added new teacher with ID: {}", savedTeacher.getTeacherId());
            return savedTeacher;
        } catch (DataAccessException e) {
            log.error("Data access error while adding teacher with ID: {}", teacher.getTeacherId(), e);
            throw new RuntimeException("Failed to add teacher due to a database issue.", e);
        } catch (IllegalArgumentException e) {
            // Re-throw specific business exceptions
            throw e;
        }
    }
}