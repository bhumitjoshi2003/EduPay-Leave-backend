package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.SupportTicket;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, Long>, JpaSpecificationExecutor<SupportTicket> {
    Page<SupportTicket> findBySchoolIdAndReporterUserIdOrderByCreatedAtDesc(Long schoolId, String reporterUserId, Pageable pageable);
    Optional<SupportTicket> findByIdAndSchoolIdAndReporterUserId(Long id, Long schoolId, String reporterUserId);
}
