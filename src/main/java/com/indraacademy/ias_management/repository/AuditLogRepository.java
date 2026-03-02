package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
}