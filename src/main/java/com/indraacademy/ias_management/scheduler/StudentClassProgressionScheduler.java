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

import java.time.Year;
import java.util.List;

@Service // or @Component if you prefer
public class StudentClassProgressionScheduler {

    private static final Logger log = LoggerFactory.getLogger(StudentClassProgressionScheduler.class);

    @Autowired
    private StudentRepository studentRepository;

    /**
     * Runs every year on 26th March at 00:00 to promote students to the next class.
     */
    @Transactional
    @Scheduled(cron = "0 0 0 26 3 *")
    public void incrementStudentClasses() {
        log.info("Starting scheduled student class progression for new academic year.");
        Year currentYear = Year.now();
        int currentAcademicYear = currentYear.getValue();

        try {
            List<Student> students = studentRepository.findByStatus("ACTIVE");
            int promotedCount = 0;

            for (Student student : students) {
                String currentClass = student.getClassName();
                String nextClass = determineNextClass(currentClass);

                if (nextClass == null) {
                    log.info("Skipping student ID {} — final class reached ({})",
                            student.getStudentId(), currentClass);
                    continue;
                }

                log.debug("Promoting {} from {} → {}",
                        student.getStudentId(), currentClass, nextClass);

                student.setClassName(nextClass);
                studentRepository.save(student);
                promotedCount++;
            }

            log.info("Promotion completed. Total students promoted: {}", promotedCount);

        } catch (DataAccessException e) {
            log.error("Database error during student promotion", e);
        } catch (Exception e) {
            log.error("Unexpected error during student promotion", e);
        }
    }

    private String determineNextClass(String currentClass) {
        if (currentClass == null || currentClass.trim().isEmpty())
            return null;

        switch (currentClass.toUpperCase()) {
            case "NURSERY": return "LKG";
            case "LKG": return "UKG";
            case "UKG": return "1";
        }
        try {
            int classNum = Integer.parseInt(currentClass);
            if (classNum >= 1 && classNum < 12) return String.valueOf(classNum + 1);

            if (classNum == 12) return null; // Graduated

            return null;
        } catch (NumberFormatException e) {
            log.warn("Invalid class format '{}' — skipping promotion.", currentClass);
            return null;
        }
    }
}
