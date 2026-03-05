package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface AuditRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {

    @Query("""
    SELECT a FROM AuditLog a
    WHERE a.timestamp < :cutoff
    """)
    List<AuditLog> findLogsOlderThan(LocalDateTime cutoff);

    @Modifying
    @Query("""
    DELETE FROM AuditLog a
    WHERE a.timestamp < :cutoff
    """)
    void deleteLogsOlderThan(LocalDateTime cutoff);
}