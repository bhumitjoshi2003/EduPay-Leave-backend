package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentFees;
import com.indraacademy.ias_management.repository.StudentFeesRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class StudentFeesGenerationService {

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private StudentFeesRepository studentFeesRepository;

    @Transactional
    @Scheduled(cron = "0 0 0 1 3 *")
    public void generateStudentFeesForNextYear() {
        Year currentYear = Year.now();
        int currentYearValue = currentYear.getValue();
        String nextAcademicYear = currentYear.format(DateTimeFormatter.ofPattern("yyyy")) + "-" +
                currentYear.plusYears(1).format(DateTimeFormatter.ofPattern("yyyy"));

        List<Student> allStudents = studentRepository.findAll();

        for (Student student : allStudents) {
            LocalDateTime createdAt = student.getCreatedAt();
            String currentClass = student.getClassName();
            String nextClass = determineNextClass(currentClass);

            if (createdAt == null || createdAt.getYear() < currentYearValue) {
                if (nextClass != null) { // Skip students who are in class 12
                    for (int month = 1; month <= 12; month++) {
                        StudentFees studentFees = new StudentFees();
                        studentFees.setStudentId(student.getStudentId());
                        studentFees.setClassName(nextClass);
                        studentFees.setMonth(month);
                        studentFees.setPaid(false);
                        studentFees.setTakesBus(student.getTakesBus());
                        studentFees.setYear(nextAcademicYear);
                        studentFees.setDistance(student.getDistance());
                        studentFees.setManuallyPaid(false);
                        studentFees.setManualPaymentReceived(null);
                        studentFeesRepository.save(studentFees);
                    }
                    System.out.println("Generated fees for student ID: " + student.getStudentId() + " for the year " + nextAcademicYear + " and next class " + nextClass);
                } else {
                    System.out.println("Skipping fee generation for student ID: " + student.getStudentId() + " as they are in Class 12 or beyond.");
                }
            } else {
                System.out.println("Skipping fee generation for student ID: " + student.getStudentId() + " as they were created in the current year (" + currentYearValue + ")");
            }
        }
        System.out.println("Student fees generation completed for the year " + nextAcademicYear);
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
                } else {
                    return null;
                }
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}