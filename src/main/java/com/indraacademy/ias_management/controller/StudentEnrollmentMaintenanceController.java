package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.StudentEnrollmentBackfillReport;
import com.indraacademy.ias_management.dto.StudentEnrollmentBulkBackfillReport;
import com.indraacademy.ias_management.service.StudentEnrollmentBackfillService;
import com.indraacademy.ias_management.service.StudentEnrollmentBulkBackfillService;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/maintenance/student-enrollments")
public class StudentEnrollmentMaintenanceController {

    private final StudentEnrollmentBackfillService backfillService;
    private final StudentEnrollmentBulkBackfillService bulkBackfillService;
    private final SecurityUtil securityUtil;

    public StudentEnrollmentMaintenanceController(
            StudentEnrollmentBackfillService backfillService,
            StudentEnrollmentBulkBackfillService bulkBackfillService,
            SecurityUtil securityUtil) {
        this.backfillService = backfillService;
        this.bulkBackfillService = bulkBackfillService;
        this.securityUtil = securityUtil;
    }

    @PostMapping("/backfill-current-state")
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    public StudentEnrollmentBackfillReport backfillCurrentState(
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate) {
        return backfillService.backfillForSchool(
                securityUtil.getSchoolId(),
                asOfDate == null ? LocalDate.now() : asOfDate,
                dryRun);
    }

    /**
     * One-time production bootstrap mechanism: loops the unchanged per-school
     * {@link StudentEnrollmentBackfillService#backfillForSchool} over every active school (or an
     * explicit {@code schoolIds} subset for chunked rollouts), so an operator does not need to
     * authenticate into every tenant individually. SUPER_ADMIN-only — this crosses tenant
     * boundaries by design, unlike every other endpoint in this controller.
     */
    @PostMapping("/backfill-active-schools")
    @PreAuthorize("hasRole('" + Role.SUPER_ADMIN + "')")
    public StudentEnrollmentBulkBackfillReport backfillActiveSchools(
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate,
            @RequestParam(required = false) List<Long> schoolIds) {
        return bulkBackfillService.backfillActiveSchools(
                asOfDate == null ? LocalDate.now() : asOfDate,
                dryRun,
                schoolIds);
    }
}
