package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.StudentRepository;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.Year;
import java.util.List;

@Service
public class StudentClassProgressionService {

    @Autowired
    private StudentRepository studentRepository;

    @Transactional
    @Scheduled(cron = "0 0 0 12 12 *") // Run at 00:00 on 1st April every year
    public void incrementStudentClasses() {
        System.out.println("System Progression Works");
        Year currentYear = Year.now();
        int currentAcademicYear = currentYear.getValue();

        List<Student> allStudents = studentRepository.findAll();
        for (Student student : allStudents) {
            LocalDateTime createdAt = student.getCreatedAt();
            if (createdAt == null || createdAt.getYear() < currentAcademicYear) {
                String currentClass = student.getClassName();
                String nextClass = determineNextClass(currentClass);
                if (nextClass != null) {
                    student.setClassName(nextClass);
                    studentRepository.save(student);
                } else {
                    System.err.println("Could not determine next class for student ID: " + student.getStudentId() + ", Class: " + currentClass);
                }
            } else {
                System.out.println("Skipping student ID: " + student.getStudentId() + " as they were created in the current academic year (" + currentAcademicYear + ")");
            }
        }
    }

    private String determineNextClass(String currentClass) {
        if ("Nursery".equals(currentClass)) {
            return "LKG";
        } else if ("LKG".equals(currentClass)) {
            return "UKG";
        } else if ("UKG".equals(currentClass)) {
            return "1";
        } else {
            try {
                int classLevel = Integer.parseInt(currentClass);
                if (classLevel < 12) {
                    return String.valueOf(classLevel + 1);
                } else if (classLevel == 12) {
                    return "Graduated";
                } else {
                    return null;
                }
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}