package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentFees;
import com.indraacademy.ias_management.repository.StudentFeesRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class StudentFeesGenerationService {

    private static final Logger log = LoggerFactory.getLogger(StudentFeesGenerationService.class);

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private StudentFeesRepository studentFeesRepository;

    @Transactional
    @Scheduled(cron = "0 0 0 1 3 *") // Run at 00:00 on 1st March every year
    public void generateStudentFeesForNextYear() {
        log.info("Starting scheduled student fees generation for the next academic year.");

        Year currentYear = Year.now();
        int currentYearValue = currentYear.getValue();
        String nextAcademicYear = currentYear.format(DateTimeFormatter.ofPattern("yyyy")) + "-" +
                currentYear.plusYears(1).format(DateTimeFormatter.ofPattern("yyyy"));

        log.info("Generating fees for academic year: {}", nextAcademicYear);

        try {
            List<Student> allStudents = studentRepository.findAll();
            int feesGeneratedCount = 0;

            for (Student student : allStudents) {
                LocalDateTime createdAt = student.getCreatedAt();
                String currentClass = student.getClassName();
                String nextClass = determineNextClass(currentClass);

                // Only generate fees for students enrolled before the current year AND who are not graduating
                boolean isNewEnrollment = createdAt != null && createdAt.getYear() >= currentYearValue;

                if (!isNewEnrollment) {
                    if (nextClass != null) {
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
                        log.debug("Generated 12 months of fees for student ID: {} (Class: {}) for year: {}", student.getStudentId(), nextClass, nextAcademicYear);
                        feesGeneratedCount++;
                    } else {
                        log.info("Skipping fee generation for student ID: {} as they are in Class 12 or beyond (Graduating).", student.getStudentId());
                    }
                } else {
                    log.debug("Skipping fee generation for student ID: {} as they were created in the current year ({}).", student.getStudentId(), currentYearValue);
                }
            }
            log.info("Student fees generation completed. Fees generated for {} students for year {}.", feesGeneratedCount, nextAcademicYear);
        } catch (DataAccessException e) {
            log.error("Data access error during student fees generation schedule.", e);
            // Transactional rollback handles the partial update failure
        } catch (Exception e) {
            log.error("Unexpected error during student fees generation schedule.", e);
        }
    }

    private String determineNextClass(String currentClass) {
        if (currentClass == null || currentClass.trim().isEmpty()) {
            return null;
        }

        if ("Nursery".equalsIgnoreCase(currentClass)) {
            return "LKG";
        } else if ("LKG".equalsIgnoreCase(currentClass)) {
            return "UKG";
        } else if ("UKG".equalsIgnoreCase(currentClass)) {
            return "1";
        } else {
            try {
                int classLevel = Integer.parseInt(currentClass);
                if (classLevel < 12) {
                    return String.valueOf(classLevel + 1);
                } else {
                    return null; // Class 12 or higher -> graduating/finished
                }
            } catch (NumberFormatException e) {
                log.warn("Invalid class format '{}' encountered for fees generation.", currentClass);
                return null;
            }
        }
    }
}