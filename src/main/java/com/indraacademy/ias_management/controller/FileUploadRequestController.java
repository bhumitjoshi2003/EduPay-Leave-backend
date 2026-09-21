package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.UploadCompleteRequest;
import com.indraacademy.ias_management.dto.UploadCompleteResponse;
import com.indraacademy.ias_management.dto.UploadRequestRequest;
import com.indraacademy.ias_management.dto.UploadRequestResponse;
import com.indraacademy.ias_management.service.FileUploadRequestService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Direct-to-object-storage upload flow, Phase 1 — see FileUploadRequestService for the full
 * authorization/verification model. Every purpose-specific check (role, school, entity
 * ownership) happens in the service, not here, since different purposes may need different
 * rules; this controller only requires a genuinely authenticated caller.
 *
 * <p>Deliberately separate from the existing {@link FileUploadController} (which still owns the
 * legacy event-image multipart upload, untouched) — this is new, additive surface area.
 */
@RestController
@RequestMapping("/api/files")
public class FileUploadRequestController {

    @Autowired private FileUploadRequestService fileUploadRequestService;

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/upload-request")
    public ResponseEntity<UploadRequestResponse> createUploadRequest(@Valid @RequestBody UploadRequestRequest request) {
        return ResponseEntity.ok(fileUploadRequestService.createUploadRequest(request));
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/complete")
    public ResponseEntity<UploadCompleteResponse> completeUpload(@Valid @RequestBody UploadCompleteRequest request) {
        return ResponseEntity.ok(fileUploadRequestService.completeUpload(request));
    }
}
