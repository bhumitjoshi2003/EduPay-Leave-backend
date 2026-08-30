package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.ParentDtos;
import com.indraacademy.ias_management.service.AuditService;
import com.indraacademy.ias_management.service.ParentPortalService;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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
    public List<ParentDtos.ParentSummary> list() { return parentPortalService.listParents(); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ParentDtos.ParentProfile create(@Valid @RequestBody ParentDtos.CreateParentRequest request,
                                            HttpServletRequest httpRequest) {
        ParentDtos.ParentProfile created = parentPortalService.createParent(request);
        audit("CREATE_PARENT_ACCOUNT", "Parent", request.parentId(), null,
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
        boolean pickupWasAuthorized = parentPortalService.getParent(parentId).children().stream()
                .filter(child -> child.studentId().equals(request.studentId()))
                .findFirst()
                .map(ParentDtos.ChildAccess::pickupAuthorized)
                .orElse(false);
        ParentDtos.ParentProfile profile = parentPortalService.linkStudent(parentId, request);
        audit("LINK_PARENT_STUDENT", "ParentStudentRelationship",
                parentId + ":" + request.studentId(), null,
                "Student linked with relationship " + request.relationshipType(), httpRequest);
        if (pickupWasAuthorized != request.pickupAuthorized()) {
            audit("CHANGE_PICKUP_AUTHORIZATION", "ParentStudentRelationship",
                    parentId + ":" + request.studentId(),
                    pickupWasAuthorized ? "AUTHORIZED" : "NOT_AUTHORIZED",
                    request.pickupAuthorized() ? "AUTHORIZED" : "NOT_AUTHORIZED", httpRequest);
        }
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

    @GetMapping("/me/profile")
    @PreAuthorize("hasRole('PARENT')")
    public ParentDtos.ParentProfile me() { return parentPortalService.myProfile(); }

    private void audit(String action, String entity, String entityId,
                       String oldValue, String newValue, HttpServletRequest request) {
        auditService.log(securityUtil.getUsername(), securityUtil.getRole(), action, entity, entityId,
                oldValue, newValue, request.getRemoteAddr());
    }
}
