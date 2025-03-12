package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.entity.BusFees;
import com.indraacademy.ias_management.service.BusFeesService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/bus-fees")
@CrossOrigin(origins = "http://localhost:4200")
public class BusFeesController {

    @Autowired
    private BusFeesService busFeesService;

    @GetMapping("")
    public ResponseEntity<List<BusFees>> getAllRecords() {
        return ResponseEntity.ok(busFeesService.getAllRecords());
    }

    @GetMapping("/{academicYear}")
    public ResponseEntity<List<BusFees>> getFeesByYear(@PathVariable String academicYear) {
        List<BusFees> fees = busFeesService.getBusFeesByAcademicYear(academicYear);
        return ResponseEntity.ok(fees);
    }

    @PutMapping("/{academicYear}")
    public ResponseEntity<List<BusFees>> updateFees(@PathVariable String academicYear, @RequestBody List<BusFees> updatedFees) {
        return ResponseEntity.ok(busFeesService.updateBusFees(academicYear, updatedFees));
    }
}
