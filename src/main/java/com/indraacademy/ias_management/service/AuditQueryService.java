package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.AuditFilterDTO;
import com.indraacademy.ias_management.entity.AuditLog;
import com.indraacademy.ias_management.repository.AuditRepository;
import com.indraacademy.ias_management.specification.AuditSpecification;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditQueryService {

    @Autowired
    private AuditRepository auditRepository;

    @Autowired
    private SecurityUtil securityUtil;

    @Transactional(readOnly = true)
    public Page<AuditLog> getAuditLogs(AuditFilterDTO filter, Pageable pageable) {
        String role = securityUtil.getRole();

        // ADMIN and SUB_ADMIN are always restricted to their own school
        if ("ADMIN".equals(role) || "SUB_ADMIN".equals(role)) {
            filter.setSchoolId(securityUtil.getSchoolId());
        }
        // SUPER_ADMIN can optionally filter by schoolId passed as query param (already in filter)

        return auditRepository.findAll(
                AuditSpecification.filter(filter),
                pageable
        );
    }
}