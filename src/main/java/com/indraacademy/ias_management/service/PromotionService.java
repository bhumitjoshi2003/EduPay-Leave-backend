package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.StudentRepository;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@Service
public class PromotionService {

    private static final Logger log = LoggerFactory.getLogger(PromotionService.class);

    private final StudentRepository studentRepository;

    @Autowired
    public PromotionService(StudentRepository studentRepository) {
        this.studentRepository = studentRepository;
    }

    @Scheduled(cron = "0 11 11 22 4 *")
    @Transactional
    public void performSpecificClassUpdateOnStartup() {
        log.info("Starting scheduled specific class update (Class '2' -> '3').");

        try {
            List<Student> studentsToUpdate = studentRepository.findByClassName("2");
            log.info("Found {} students in class '2' to update.", studentsToUpdate.size());

            for (Student student : studentsToUpdate) {
                student.setClassName("3");
            }

            if (!studentsToUpdate.isEmpty()) {
                studentRepository.saveAll(studentsToUpdate);
                log.info("Successfully promoted {} students from class '2' to '3'.", studentsToUpdate.size());
            } else {
                log.info("No students found in class '2' for promotion.");
            }
        } catch (DataAccessException e) {
            log.error("Data access error during specific class update schedule.", e);
            // Transactional rollback handles the partial update failure
        } catch (Exception e) {
            log.error("Unexpected error during specific class update schedule.", e);
        }
    }
}