package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.repository.AiTraceEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Deletes AI trace events older than the retention window. Kept short by default
 * (see application.properties) since user_message/final_reply can contain
 * student-specific detail — this table is an ops-debugging aid, not an archive.
 */
@Service
public class AiTraceCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(AiTraceCleanupScheduler.class);

    @Value("${ai.trace.retention-days:30}")
    private int retentionDays;

    @Autowired private AiTraceEventRepository repository;

    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Kolkata")
    @Transactional
    public void deleteExpiredTraces() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        long deleted = repository.deleteByCreatedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("AI trace cleanup: deleted {} event(s) older than {} days.", deleted, retentionDays);
        }
    }
}
