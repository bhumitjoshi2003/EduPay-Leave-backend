package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.service.ClassIdBackfillService;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class ClassIdMigrationController {

    @Autowired private ClassIdBackfillService backfillService;
    @Autowired private SecurityUtil securityUtil;

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PostMapping("/backfill-class-ids")
    public ResponseEntity<Map<String, Integer>> backfillClassIds() {
        Long schoolId = securityUtil.getSchoolId();
        Map<String, Integer> result = backfillService.backfillForSchool(schoolId);
        return ResponseEntity.ok(result);
    }
}
