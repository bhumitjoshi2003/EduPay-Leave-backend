package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.FeeStructureRuleDto;
import com.indraacademy.ias_management.service.FeeRuleService;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.ParentPortalService;
import com.indraacademy.ias_management.repository.StudentRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/fee-rules")
public class FeeRuleController {

    @Autowired
    private FeeRuleService feeRuleService;
    @Autowired private AuthService authService;
    @Autowired private ParentPortalService parentPortalService;
    @Autowired private StudentRepository studentRepository;

    @GetMapping("/session/{sessionId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'STUDENT', 'SUB_ADMIN', 'PARENT')")
    public ResponseEntity<List<FeeStructureRuleDto>> getRulesBySession(@PathVariable Long sessionId) {
        if ("PARENT".equals(authService.getRole())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "Parents must request the fee structure for a linked student");
        }
        return ResponseEntity.ok(feeRuleService.getRulesBySession(sessionId));
    }

    @GetMapping("/session/{sessionId}/class/{className}")
    @PreAuthorize("hasAnyRole('ADMIN', 'STUDENT', 'SUB_ADMIN', 'PARENT')")
    public ResponseEntity<List<FeeStructureRuleDto>> getRulesBySessionAndClass(
            @PathVariable Long sessionId, @PathVariable String className,
            @RequestParam(required = false) String studentId) {
        if ("PARENT".equals(authService.getRole())) {
            if (studentId == null || studentId.isBlank()) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.FORBIDDEN, "A linked student is required");
            }
            parentPortalService.assertChildAccess(studentId, ParentPortalService.ChildPermission.FEES);
            var student = studentRepository.findByStudentIdAndSchoolId(
                            studentId, com.indraacademy.ias_management.util.SchoolContext.get())
                    .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                            org.springframework.http.HttpStatus.FORBIDDEN, "You do not have access to this student"));
            if (!className.equals(student.getClassName())) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.FORBIDDEN,
                        "Parents can only view their linked child's fee structure");
            }
        }
        return ResponseEntity.ok(feeRuleService.getRulesBySessionAndClass(sessionId, className));
    }

    @PutMapping("/session/{sessionId}/class/{className}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<FeeStructureRuleDto>> saveRulesForClass(
            @PathVariable Long sessionId,
            @PathVariable String className,
            @Valid @RequestBody List<FeeStructureRuleDto> rules,
            HttpServletRequest request) {
        return ResponseEntity.ok(feeRuleService.saveRulesForClass(sessionId, className, rules, request));
    }
}
