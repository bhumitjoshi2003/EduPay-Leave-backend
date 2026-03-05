package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.AuditLog;
import com.indraacademy.ias_management.entity.AuditLogArchive;
import com.indraacademy.ias_management.repository.AuditArchiveRepository;
import com.indraacademy.ias_management.repository.AuditRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AuditRetentionService {

    @Autowired
    private AuditRepository auditLogRepository;
    @Autowired private AuditArchiveRepository archiveLogRepository;

    @Transactional
    @Scheduled(cron = "0 30 3 ? * SUN")
    public void archiveOldLogs() {

        LocalDateTime cutoff =
                LocalDateTime.now().minusMonths(6);

        List<AuditLog> oldLogs =
                auditLogRepository.findLogsOlderThan(cutoff);

        if (oldLogs.isEmpty()) return;

        List<AuditLogArchive> archiveLogs =
                oldLogs.stream()
                        .map(AuditLogArchive::fromAuditLog)
                        .toList();

        archiveLogRepository.saveAll(archiveLogs);

        auditLogRepository.deleteLogsOlderThan(cutoff);
    }

    @Scheduled(cron = "0 30 3 ? * SUN")
    public void deleteVeryOldLogs(){

        LocalDateTime cutoff =
                LocalDateTime.now().minusYears(2);

        archiveLogRepository.deleteLogsOlderThan(cutoff);
    }
}