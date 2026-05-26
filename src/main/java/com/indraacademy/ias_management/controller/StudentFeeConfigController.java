package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.StudentFeeConfigDto;
import com.indraacademy.ias_management.service.StudentFeeConfigService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/student-fee-configs")
public class StudentFeeConfigController {

    @Autowired
    private StudentFeeConfigService configService;

    @GetMapping("/{studentId}/session/{sessionId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'STUDENT')")
    public ResponseEntity<List<StudentFeeConfigDto>> getStudentConfigs(
            @PathVariable String studentId, @PathVariable Long sessionId) {
        return ResponseEntity.ok(configService.getStudentConfigs(studentId, sessionId));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<StudentFeeConfigDto> createConfig(
            @Valid @RequestBody StudentFeeConfigDto dto, HttpServletRequest request) {
        return ResponseEntity.ok(configService.createConfig(dto, request));
    }

    @DeleteMapping("/{configId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteConfig(
            @PathVariable Long configId, HttpServletRequest request) {
        configService.deleteConfig(configId, request);
        return ResponseEntity.noContent().build();
    }
}
