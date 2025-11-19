package com.indraacademy.ias_management.scheduler;

import com.indraacademy.ias_management.repository.StudentRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class StudentStatusScheduler {

    @Autowired
    private StudentRepository studentRepository;

//    @Scheduled(cron = "0 26 14 19 11 *")
    // Runs every day at 4:00 AM IST
    @Scheduled(cron = "0 0 4 * * *")
    @Transactional
    public void updateStudentStatuses() {
        LocalDate today = LocalDate.now();

        studentRepository.updateStatusUpcoming(today);

        studentRepository.updateStatusInactive(today);

        studentRepository.updateStatusActive(today);
    }
}
