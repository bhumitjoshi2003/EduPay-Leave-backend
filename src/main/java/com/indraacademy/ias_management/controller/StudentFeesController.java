package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.entity.StudentFees;
import com.indraacademy.ias_management.service.StudentFeesService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/student-fees")
@CrossOrigin(origins = "http://localhost:4200")
public class StudentFeesController {

    @Autowired
    private StudentFeesService studentFeesService;

    @GetMapping("/{studentId}/{year}")
    public ResponseEntity<List<StudentFees>> getStudentFees(
            @PathVariable String studentId,
            @PathVariable String year) {
        return ResponseEntity.ok(studentFeesService.getStudentFees(studentId, year));
    }

    @PutMapping("/")
    public ResponseEntity<StudentFees> updateStudentFees(@RequestBody StudentFees studentFees) {
        return ResponseEntity.ok(studentFeesService.updateStudentFees(studentFees));
    }

    @PostMapping("/")
    public ResponseEntity<StudentFees> createStudentFees(@RequestBody StudentFees studentFees) {
        return ResponseEntity.ok(studentFeesService.createStudentFees(studentFees));
    }

    @GetMapping("/{studentId}/{year}/{month}")
    public ResponseEntity<StudentFees> getStudentFee(
            @PathVariable String studentId,
            @PathVariable String year,
            @PathVariable Integer month) {
        Optional<StudentFees> studentFee = studentFeesService.getStudentFee(studentId, year, month);
        return studentFee.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/sessions/{studentId}")
    public ResponseEntity<List<String>> getDistinctYearsByStudentId(@PathVariable String studentId) {
        List<String> sessions = studentFeesService.getDistinctYearsByStudentId(studentId);
        System.out.println(sessions);
        return ResponseEntity.ok(sessions);
    }
}