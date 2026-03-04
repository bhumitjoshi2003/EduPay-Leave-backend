package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AuditRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {
}