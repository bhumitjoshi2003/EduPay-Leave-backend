package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.AuditLogArchive;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface AuditArchiveRepository extends JpaRepository<AuditLogArchive, Long> {

    @Modifying
    @Query("""
    DELETE FROM AuditLogArchive a
    WHERE a.timestamp < :cutoff
    """)
    void deleteLogsOlderThan(LocalDateTime cutoff);
}