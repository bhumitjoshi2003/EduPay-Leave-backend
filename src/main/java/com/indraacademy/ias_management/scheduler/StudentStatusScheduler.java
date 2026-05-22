package com.indraacademy.ias_management.scheduler;

import com.indraacademy.ias_management.repository.StudentRepository;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
public class StudentStatusScheduler {

    private static final Logger log = LoggerFactory.getLogger(StudentStatusScheduler.class);

    @Autowired
    private StudentRepository studentRepository;

    /**
     * Guards against concurrent execution within the same JVM instance.
     * For multi-instance deployments (horizontal scaling) use ShedLock or
     * a similar distributed lock to prevent simultaneous runs across nodes.
     */
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

//    @Scheduled(cron = "0 26 14 19 11 *")
    // Runs every day at 4:00 AM IST
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Kolkata")
    @Transactional
    public void updateStudentStatuses() {
        if (!isRunning.compareAndSet(false, true)) {
            log.warn("StudentStatusScheduler is already running — skipping this trigger.");
            return;
        }
        try {
            LocalDate today = LocalDate.now();
            log.info("Running StudentStatusScheduler for date: {}", today);

            studentRepository.updateStatusUpcoming(today);
            studentRepository.updateStatusInactive(today);
            studentRepository.updateStatusActive(today);

            log.info("StudentStatusScheduler completed successfully.");
        } finally {
            isRunning.set(false);
        }
    }
}
