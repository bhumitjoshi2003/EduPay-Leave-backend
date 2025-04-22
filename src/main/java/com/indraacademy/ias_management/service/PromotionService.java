package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.StudentRepository;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.time.LocalDateTime;

@Service
public class PromotionService {

    private final StudentRepository studentRepository;

    @Autowired
    public PromotionService(StudentRepository studentRepository) {
        this.studentRepository = studentRepository;
    }

    @Scheduled(cron = "0 11 11 22 4 *")
    @Transactional
    public void performSpecificClassUpdateOnStartup() {
        List<Student> studentsToUpdate = studentRepository.findByClassName("2");
        for (Student student : studentsToUpdate) {
            student.setClassName("3");
        }
        studentRepository.saveAll(studentsToUpdate);
    }
}