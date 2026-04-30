package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.AuditFilterDTO;
import com.indraacademy.ias_management.entity.AuditLog;
import com.indraacademy.ias_management.repository.AuditRepository;
import com.indraacademy.ias_management.specification.AuditSpecification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditQueryService {

    @Autowired
    private AuditRepository auditRepository;

    @Transactional(readOnly = true)
    public Page<AuditLog> getAuditLogs(AuditFilterDTO filter, Pageable pageable) {
        return auditRepository.findAll(
                AuditSpecification.filter(filter),
                pageable
        );
    }
}