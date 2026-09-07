package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.LegacyAdoptionDtos.InvariantSnapshot;
import com.indraacademy.ias_management.dto.LegacyAdoptionDtos.ResponsibilityAdoptionReport;
import com.indraacademy.ias_management.dto.LegacyAdoptionDtos.TimetableAdoptionReport;
import com.indraacademy.ias_management.service.LegacyAdoptionInvariantService;
import com.indraacademy.ias_management.service.LegacyResponsibilityAdoptionService;
import com.indraacademy.ias_management.service.LegacyTimetableAdoptionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase F5A: one-time, cross-tenant maintenance mechanism for adopting PROD's pre-F2 legacy
 * timetable/class-teacher data into the session-scoped model — SUPER_ADMIN-only, same rationale as
 * {@link StudentEnrollmentMaintenanceController}'s bulk endpoint: this crosses tenant boundaries by
 * design (an explicit {@code schoolId} parameter, never {@code SecurityUtil.getSchoolId()}), unlike
 * every ADMIN-scoped endpoint elsewhere in this codebase. {@code dryRun} defaults to {@code true}
 * on every mutating endpoint so an operator must deliberately opt into a real write.
 */
@RestController
@RequestMapping("/api/maintenance/legacy-adoption")
public class LegacyAdoptionMaintenanceController {

    private final LegacyTimetableAdoptionService timetableAdoptionService;
    private final LegacyResponsibilityAdoptionService responsibilityAdoptionService;
    private final LegacyAdoptionInvariantService invariantService;

    public LegacyAdoptionMaintenanceController(
            LegacyTimetableAdoptionService timetableAdoptionService,
            LegacyResponsibilityAdoptionService responsibilityAdoptionService,
            LegacyAdoptionInvariantService invariantService) {
        this.timetableAdoptionService = timetableAdoptionService;
        this.responsibilityAdoptionService = responsibilityAdoptionService;
        this.invariantService = invariantService;
    }

    @PostMapping("/timetable")
    @PreAuthorize("hasRole('" + Role.SUPER_ADMIN + "')")
    public TimetableAdoptionReport adoptTimetable(
            @RequestParam Long schoolId,
            @RequestParam Long academicSessionId,
            @RequestParam(defaultValue = "true") boolean dryRun) {
        return timetableAdoptionService.adopt(schoolId, academicSessionId, dryRun);
    }

    @PostMapping("/class-teacher-responsibility")
    @PreAuthorize("hasRole('" + Role.SUPER_ADMIN + "')")
    public ResponsibilityAdoptionReport adoptResponsibility(
            @RequestParam Long schoolId,
            @RequestParam Long academicSessionId,
            @RequestParam(defaultValue = "true") boolean dryRun) {
        return responsibilityAdoptionService.adopt(schoolId, academicSessionId, dryRun);
    }

    /** Read-only fingerprint of everything adoption could touch — call before and after a dry
     *  run (or, for a future real run, before/after each transaction) and diff the two results to
     *  prove non-mutation (or to prove exactly what a real run changed). */
    @GetMapping("/invariant-snapshot")
    @PreAuthorize("hasRole('" + Role.SUPER_ADMIN + "')")
    public InvariantSnapshot invariantSnapshot(
            @RequestParam Long schoolId,
            @RequestParam Long academicSessionId) {
        return invariantService.capture(schoolId, academicSessionId);
    }
}
