package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.ParentDtos;
import com.indraacademy.ias_management.dto.ParentFilterDTO;
import com.indraacademy.ias_management.service.AuditService;
import com.indraacademy.ias_management.service.ParentPortalService;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/parents")
public class ParentPortalController {
    private final ParentPortalService parentPortalService;
    private final AuditService auditService;
    private final SecurityUtil securityUtil;

    public ParentPortalController(ParentPortalService parentPortalService,
                                  AuditService auditService,
                                  SecurityUtil securityUtil) {
        this.parentPortalService = parentPortalService;
        this.auditService = auditService;
        this.securityUtil = securityUtil;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Page<ParentDtos.ParentSummary> list(ParentFilterDTO filter, Pageable pageable) {
        return parentPortalService.listParentsPaged(filter, pageable);
    }

    @GetMapping("/stats")
    @PreAuthorize("hasRole('ADMIN')")
    public ParentDtos.ParentDirectoryStats stats() { return parentPortalService.directoryStats(); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ParentDtos.ParentProfile create(@Valid @RequestBody ParentDtos.CreateParentRequest request,
                                            HttpServletRequest httpRequest) {
        ParentDtos.ParentProfile created = parentPortalService.createParent(request);
        audit("CREATE_PARENT_ACCOUNT", "Parent", created.parent().parentId(), null,
                "Parent account created", httpRequest);
        return created;
    }

    @GetMapping("/{parentId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ParentDtos.ParentProfile get(@PathVariable String parentId) {
        return parentPortalService.getParent(parentId);
    }

    @PostMapping("/{parentId}/children")
    @PreAuthorize("hasRole('ADMIN')")
    public ParentDtos.ParentProfile link(@PathVariable String parentId,
                                         @Valid @RequestBody ParentDtos.LinkStudentRequest request,
                                         HttpServletRequest httpRequest) {
        ParentDtos.ParentProfile profile = parentPortalService.linkStudent(parentId, request);
        audit("LINK_PARENT_STUDENT", "ParentStudentRelationship",
                parentId + ":" + request.studentId(), null,
                "Student linked with relationship " + request.relationshipType(), httpRequest);
        return profile;
    }

    @DeleteMapping("/{parentId}/children/{relationshipId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void unlink(@PathVariable String parentId, @PathVariable Long relationshipId,
                       HttpServletRequest httpRequest) {
        parentPortalService.unlinkStudent(parentId, relationshipId);
        audit("UNLINK_PARENT_STUDENT", "ParentStudentRelationship", relationshipId.toString(),
                "Active relationship", "Relationship ended", httpRequest);
    }

    @PatchMapping("/{parentId}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> setStatus(@PathVariable String parentId,
                                         @RequestBody Map<String, Boolean> body,
                                         HttpServletRequest httpRequest) {
        boolean active = Boolean.TRUE.equals(body.get("active"));
        parentPortalService.setParentActive(parentId, active);
        audit("CHANGE_PARENT_ACCOUNT_STATUS", "Parent", parentId, null,
                active ? "ACTIVE" : "INACTIVE", httpRequest);
        return Map.of("parentId", parentId, "active", active);
    }

    @PostMapping("/{parentId}/reset-password")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, String> resetPassword(@PathVariable String parentId,
                                             @Valid @RequestBody ParentDtos.ResetPasswordRequest request,
                                             HttpServletRequest httpRequest) {
        parentPortalService.resetPassword(parentId, request.temporaryPassword());
        audit("RESET_PARENT_PASSWORD", "User", parentId, null,
                "Temporary password reset; mustChangePassword=true", httpRequest);
        return Map.of("parentId", parentId, "status", "reset");
    }

    @GetMapping("/me/profile")
    @PreAuthorize("hasRole('PARENT')")
    public ParentDtos.ParentProfile me() { return parentPortalService.myProfile(); }

    private void audit(String action, String entity, String entityId,
                       String oldValue, String newValue, HttpServletRequest request) {
        auditService.log(securityUtil.getUsername(), securityUtil.getRole(), action, entity, entityId,
                oldValue, newValue, request.getRemoteAddr());
    }
}
