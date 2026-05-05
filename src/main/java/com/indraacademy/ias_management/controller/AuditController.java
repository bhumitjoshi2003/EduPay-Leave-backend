package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.AuditFilterDTO;
import com.indraacademy.ias_management.entity.AuditLog;
import com.indraacademy.ias_management.service.AuditQueryService;
import com.indraacademy.ias_management.entity.Role;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    @Autowired
    private AuditQueryService auditQueryService;

    @GetMapping
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUB_ADMIN + "', '" + Role.SUPER_ADMIN + "')")
    public Page<AuditLog> getAuditLogs(
            AuditFilterDTO filter,
            Pageable pageable
    ) {
        return auditQueryService.getAuditLogs(filter, pageable);
    }
}