package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.AuditLog;
import com.indraacademy.ias_management.repository.AuditRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class AuditService {

    @Autowired
    private AuditRepository auditLogRepository;

    public void log(
            String username,
            String role,
            String action,
            String entityName,
            String entityId,
            String oldValue,
            String newValue,
            String ip
    ) {
        AuditLog log = new AuditLog();
        log.setUsername(username);
        log.setRole(role);
        log.setAction(action);
        log.setEntityName(entityName);
        log.setEntityId(entityId);
        log.setOldValue(oldValue);
        log.setNewValue(newValue);
        log.setIpAddress(ip);
        log.setTimestamp(LocalDateTime.now());

        auditLogRepository.save(log);
    }
}