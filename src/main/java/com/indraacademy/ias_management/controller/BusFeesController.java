package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.entity.BusFees;
import com.indraacademy.ias_management.service.BusFeesService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/bus-fees")
@CrossOrigin(origins = "http://localhost:4200")
public class BusFeesController {

    private static final Logger log = LoggerFactory.getLogger(BusFeesController.class);

    @Autowired
    private BusFeesService busFeesService;

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
        try {
            BigDecimal fees = busFeesService.getBusFeesOfDistance(distance, academicYear);
            if (fees == null) {
                log.warn("Bus fees not found for distance {} and year {}", distance, academicYear);
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(fees);
        } catch (IllegalArgumentException e) {
            log.error("Invalid input for bus fees lookup: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "')")
    @PutMapping("/{academicYear}")
    public ResponseEntity<List<BusFees>> updateFees(@PathVariable String academicYear, @RequestBody List<BusFees> updatedFees) {
        log.info("Request to update bus fees for academic year: {}", academicYear);
        try {
            return ResponseEntity.ok(busFeesService.updateBusFees(academicYear, updatedFees));
        } catch (IllegalArgumentException e) {
            log.error("Invalid data provided for updating bus fees in year {}: {}", academicYear, e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Error updating bus fees for year {}.", academicYear, e);
            return ResponseEntity.internalServerError().build();
        }
    }
}