package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.entity.FeeStructure;
import com.indraacademy.ias_management.service.FeeStructureService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/fee-structure")
@CrossOrigin(origins = "http://localhost:4200")
public class FeeStructureController {

    private static final Logger log = LoggerFactory.getLogger(FeeStructureController.class);

    @Autowired
    private FeeStructureService feeStructureService;

    @GetMapping("")
    public ResponseEntity<List<FeeStructure>> getAllRecords() {
        log.info("Request to get all fee structure records.");
        return ResponseEntity.ok(feeStructureService.getAllRecords());
    }

    @GetMapping("/{academicYear}/{className}")
    public ResponseEntity<FeeStructure> getFeeStructure(@PathVariable String academicYear, @PathVariable String className) {
        log.info("Request to get fee structure for Year: {} and Class: {}", academicYear, className);
        try {
            FeeStructure feeStructure = feeStructureService.getFeeStructuresByAcademicYearAndClassName(academicYear, className);
            if (feeStructure == null) {
                log.warn("Fee structure not found for Year: {} and Class: {}", academicYear, className);
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(feeStructure);
        } catch (IllegalArgumentException e) {
            log.error("Invalid parameters for fee structure retrieval: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/{academicYear}")
    public ResponseEntity<List<FeeStructure>> getFeeStructuresByYear(@PathVariable String academicYear) {
        log.info("Request to get all fee structures for academic year: {}", academicYear);
        List<FeeStructure> feeStructures = feeStructureService.getFeeStructuresByAcademicYear(academicYear);
        return ResponseEntity.ok(feeStructures);
    }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "')")
    @PutMapping("/{academicYear}")
    public ResponseEntity<List<FeeStructure>> updateFeeStructures(@PathVariable String academicYear, @RequestBody List<FeeStructure> updatedFees) {
        log.info("Request to update fee structures for academic year: {}", academicYear);
        try {
            return ResponseEntity.ok(feeStructureService.updateFeeStructures(academicYear, updatedFees));
        } catch (IllegalArgumentException e) {
            log.error("Invalid data for fee structure update in year {}: {}", academicYear, e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (NoSuchElementException e) {
            log.error("One or more fee structures not found for year {}.", academicYear);
            return ResponseEntity.notFound().build();
        }
    }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "')")
    @PostMapping("/{academicYear}")
    public ResponseEntity<List<FeeStructure>> createNewSession(@PathVariable String academicYear, @RequestBody List<FeeStructure> newFees){
        log.info("Request to create new fee session for year: {}", academicYear);
        try {
            return new ResponseEntity<>(feeStructureService.createNewSession(academicYear,newFees), HttpStatus.CREATED);
        } catch (IllegalArgumentException e) {
            log.error("New session creation failed for year {}: {}", academicYear, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }
}