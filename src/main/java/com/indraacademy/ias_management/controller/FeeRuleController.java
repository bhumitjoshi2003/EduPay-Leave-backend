package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.FeeStructureRuleDto;
import com.indraacademy.ias_management.service.FeeRuleService;
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

    @GetMapping("/session/{sessionId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'STUDENT', 'SUB_ADMIN')")
    public ResponseEntity<List<FeeStructureRuleDto>> getRulesBySession(@PathVariable Long sessionId) {
        return ResponseEntity.ok(feeRuleService.getRulesBySession(sessionId));
    }

    @GetMapping("/session/{sessionId}/class/{className}")
    @PreAuthorize("hasAnyRole('ADMIN', 'STUDENT', 'SUB_ADMIN')")
    public ResponseEntity<List<FeeStructureRuleDto>> getRulesBySessionAndClass(
            @PathVariable Long sessionId, @PathVariable String className) {
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
