package com.indraacademy.ias_management.scheduler;

import com.indraacademy.ias_management.entity.Student;
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
import java.util.List;

@Service // or @Component if you prefer
public class StudentClassProgressionScheduler {

    private static final Logger log = LoggerFactory.getLogger(StudentClassProgressionScheduler.class);

    @Autowired
    private StudentRepository studentRepository;

    /**
     * Runs every year on 1st April at 00:00 to promote students to the next class.
     */
    @Transactional
    @Scheduled(cron = "0 0 0 1 4 *") // 00:00 on the 1st of April every year
    public void incrementStudentClasses() {
        log.info("Starting scheduled student class progression for new academic year.");
        Year currentYear = Year.now();
        int currentAcademicYear = currentYear.getValue();

        try {
            List<Student> allStudents = studentRepository.findAll();
            int promotedCount = 0;

            for (Student student : allStudents) {
                LocalDateTime createdAt = student.getCreatedAt();
                boolean isNewEnrollment = createdAt != null && createdAt.getYear() >= currentAcademicYear;

                if (!isNewEnrollment) {
                    String currentClass = student.getClassName();
                    String nextClass = determineNextClass(currentClass);

                    if (nextClass != null) {
                        log.debug("Promoting student ID: {} from class {} to {}",
                                student.getStudentId(), currentClass, nextClass);
                        student.setClassName(nextClass);
                        studentRepository.save(student);
                        promotedCount++;
                    } else {
                        log.info("Student ID: {} is graduating or already graduated (Class: {}). Skipping promotion.",
                                student.getStudentId(), currentClass);
                    }
                } else {
                    log.debug("Skipping student ID: {} as they were created in the current academic year ({}).",
                            student.getStudentId(), currentAcademicYear);
                }
            }
            log.info("Completed student class progression. Successfully promoted {} students.", promotedCount);
        } catch (DataAccessException e) {
            log.error("Data access error during student class progression.", e);
        } catch (Exception e) {
            log.error("Unexpected error during student class progression.", e);
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
                } else if (classLevel == 12) {
                    return "Graduated";
                } else {
                    return null;
                }
            } catch (NumberFormatException e) {
                log.warn("Invalid class format '{}' encountered for promotion.", currentClass);
                return null;
            }
        }
    }
}
