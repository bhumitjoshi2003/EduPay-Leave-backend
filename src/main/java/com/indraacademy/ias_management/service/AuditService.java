package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.entity.AuditLog;
import com.indraacademy.ias_management.repository.AuditRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Service
public class AuditService {

    @Autowired
    private AuditRepository auditLogRepository;

    @Autowired
    private ObjectMapper objectMapper;

    /** Full-entity log — use for CREATE and DELETE actions. */
    @Async
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

    /**
     * Diff-aware log — use for UPDATE actions.
     * Must be @Async itself because logUpdate() calls log() on the same instance —
     * a direct this.log() call bypasses the Spring proxy, so @Async on log() alone
     * would be ignored for this code path.
     *
     * Compares {@code oldJson} and {@code newJson} field-by-field.
     * If nothing changed, the audit entry is skipped entirely.
     * Otherwise, only the changed fields are stored in oldValue / newValue.
     *
     * If either JSON string cannot be parsed (e.g., it is a plain-text description
     * rather than a proper JSON object), falls back to the full-entity log behavior.
     */
    @Async
    public void logUpdate(
            String username,
            String role,
            String action,
            String entityName,
            String entityId,
            String oldJson,
            String newJson,
            String ip
    ) {
        try {
            TypeReference<Map<String, Object>> mapType = new TypeReference<>() {};
            Map<String, Object> oldMap = oldJson != null
                    ? objectMapper.readValue(oldJson, mapType)
                    : Map.of();
            Map<String, Object> newMap = newJson != null
                    ? objectMapper.readValue(newJson, mapType)
                    : Map.of();

            Map<String, Object> changedOld = new LinkedHashMap<>();
            Map<String, Object> changedNew = new LinkedHashMap<>();

            // Walk all keys in the new snapshot; also check keys only in old snapshot
            for (String key : newMap.keySet()) {
                Object oldVal = oldMap.get(key);
                Object newVal = newMap.get(key);
                if (!Objects.equals(oldVal, newVal)) {
                    changedOld.put(key, oldVal);
                    changedNew.put(key, newVal);
                }
            }
            for (String key : oldMap.keySet()) {
                if (!newMap.containsKey(key)) {
                    changedOld.put(key, oldMap.get(key));
                    changedNew.put(key, null);
                }
            }

            if (changedOld.isEmpty()) {
                // Nothing actually changed — skip the audit entry
                return;
            }

            log(username, role, action, entityName, entityId,
                    objectMapper.writeValueAsString(changedOld),
                    objectMapper.writeValueAsString(changedNew),
                    ip);

        } catch (JsonProcessingException | IllegalArgumentException e) {
            // Fallback: one of the JSON strings was not a proper object (e.g., plain text).
            // Store as-is so we don't silently lose the audit trail.
            log(username, role, action, entityName, entityId, oldJson, newJson, ip);
        }
    }
}
