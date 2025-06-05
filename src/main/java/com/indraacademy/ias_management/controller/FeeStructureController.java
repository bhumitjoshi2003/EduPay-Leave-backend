package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.entity.FeeStructure;
import com.indraacademy.ias_management.service.FeeStructureService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/fee-structure")
@CrossOrigin(origins = "http://localhost:4200")
public class FeeStructureController {

    @Autowired
    private FeeStructureService feeStructureService;

    @GetMapping("")
    public ResponseEntity<List<FeeStructure>> getAllRecords() {
        return ResponseEntity.ok(feeStructureService.getAllRecords());
    }

    @GetMapping("/{academicYear}/{className}")
    public ResponseEntity<FeeStructure> getAllRecords(@PathVariable String academicYear, @PathVariable String className) {
        return ResponseEntity.ok(feeStructureService.getFeeStructuresByAcademicYearAndClassName(academicYear, className));
    }

    @GetMapping("/{academicYear}")
    public ResponseEntity<List<FeeStructure>> getFeeStructuresByYear(@PathVariable String academicYear) {
        List<FeeStructure> feeStructures = feeStructureService.getFeeStructuresByAcademicYear(academicYear);
        return ResponseEntity.ok(feeStructures);
    }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "')")
    @PutMapping("/{academicYear}")
    public ResponseEntity<List<FeeStructure>> updateFeeStructures(@PathVariable String academicYear, @RequestBody List<FeeStructure> updatedFees) {
        return ResponseEntity.ok(feeStructureService.updateFeeStructures(academicYear, updatedFees));
    }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "')")
    @PostMapping("/{academicYear}")
    public ResponseEntity<List<FeeStructure>> createNewSession(@PathVariable String academicYear, @RequestBody List<FeeStructure> newFees){
        return ResponseEntity.ok(feeStructureService.createNewSession(academicYear,newFees));
    }
}