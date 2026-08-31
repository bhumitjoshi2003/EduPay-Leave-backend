package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.ApplicableBusFeeDto;
import com.indraacademy.ias_management.entity.BusFees;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.BusFeesService;
import com.indraacademy.ias_management.service.ParentPortalService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/bus-fees")
@PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.STUDENT + "', '" + Role.SUB_ADMIN + "', '" + Role.PARENT + "')")
public class BusFeesController {

    private static final Logger log = LoggerFactory.getLogger(BusFeesController.class);

    @Autowired
    private BusFeesService busFeesService;
    @Autowired
    private AuthService authService;
    @Autowired
    private ParentPortalService parentPortalService;

    @GetMapping("")
    public ResponseEntity<List<BusFees>> getAllRecords() {
        log.info("Request to get all bus fee records.");
        return ResponseEntity.ok(busFeesService.getAllRecords());
    }

    @GetMapping("/{academicYear}")
    public ResponseEntity<List<BusFees>> getFeesByYear(@PathVariable String academicYear) {
        log.info("Request to get bus fees for academic year: {}", academicYear);
        List<BusFees> fees = busFeesService.getBusFeesByAcademicYear(academicYear);
        return ResponseEntity.ok(fees);
    }

    @GetMapping("/{distance}/{academicYear}")
    public ResponseEntity<BigDecimal> getBusFees(@PathVariable Double distance, @PathVariable String academicYear) {
        log.info("Request to get bus fees for distance {} in year {}", distance, academicYear);
        BigDecimal fees = busFeesService.getBusFeesOfDistance(distance, academicYear);
        if (fees == null) {
            log.warn("Bus fees not found for distance {} and year {}", distance, academicYear);
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(fees);
    }

    /** The caller's own (STUDENT) or a linked child's (PARENT, gated on FEES) resolved bus fee —
     *  never a raw distance lookup the frontend has to match against slabs itself. */
    @GetMapping("/student/{studentId}")
    public ResponseEntity<ApplicableBusFeeDto> getApplicableBusFeeForStudent(
            @PathVariable String studentId, @RequestParam String academicYear) {
        String role = authService.getRole();
        String resolvedStudentId = Role.STUDENT.equals(role) ? authService.getUserId() : studentId;
        if (Role.PARENT.equals(role)) {
            parentPortalService.assertChildAccess(resolvedStudentId, ParentPortalService.ChildPermission.FEES);
        }
        log.info("Request for applicable bus fee: student={} year={}", resolvedStudentId, academicYear);
        return ResponseEntity.ok(busFeesService.getApplicableBusFee(resolvedStudentId, academicYear));
    }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "')")
    @PutMapping("/{academicYear}")
    public ResponseEntity<List<BusFees>> updateFees(@PathVariable String academicYear, @RequestBody List<BusFees> updatedFees, HttpServletRequest request) {
        log.info("Request to update bus fees for academic year: {}", academicYear);
        return ResponseEntity.ok(busFeesService.updateBusFees(academicYear, updatedFees, request));
    }
}