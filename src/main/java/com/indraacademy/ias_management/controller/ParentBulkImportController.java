package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.ParentBulkImportDtos.ConfirmRequest;
import com.indraacademy.ias_management.dto.ParentBulkImportDtos.ConfirmResponse;
import com.indraacademy.ias_management.dto.ParentBulkImportDtos.PreviewResponse;
import com.indraacademy.ias_management.service.ParentBulkImportService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/parents/bulk-import")
@PreAuthorize("hasRole('" + Role.ADMIN + "')")
public class ParentBulkImportController {

    private static final Logger log = LoggerFactory.getLogger(ParentBulkImportController.class);

    @Autowired private ParentBulkImportService parentBulkImportService;

    /** Read-only — parses, validates and matches every row but writes nothing. */
    @PostMapping("/preview")
    public ResponseEntity<?> preview(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("Uploaded file is empty.");
        }
        PreviewResponse result = parentBulkImportService.preview(file);
        log.info("Parent bulk import preview: {} rows, {} new, {} existing-match, {} conflicts, {} invalid, {} duplicate",
                result.totalRows(), result.newParentCount(), result.existingParentMatchCount(),
                result.conflictCount(), result.invalidCount(), result.duplicateCount());
        return ResponseEntity.ok(result);
    }

    /**
     * Re-validates the same file from scratch (never trusts a client-cached preview) and
     * creates Parent accounts + relationships. {@code resolutions} carries the admin's
     * explicit decision for any row that was ambiguous at preview time — sent as a second
     * JSON part alongside the file.
     */
    @PostMapping(value = "/confirm", consumes = "multipart/form-data")
    public ResponseEntity<?> confirm(@RequestParam("file") MultipartFile file,
                                     @RequestPart(value = "resolutions", required = false) ConfirmRequest resolutions,
                                     HttpServletRequest request) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("Uploaded file is empty.");
        }
        ConfirmRequest effectiveResolutions = resolutions != null ? resolutions : new ConfirmRequest(java.util.Map.of());
        ConfirmResponse result = parentBulkImportService.confirm(file, effectiveResolutions, request);
        log.info("Parent bulk import confirmed: {} parents created, {} relationships created, {} skipped",
                result.parentsCreated(), result.relationshipsCreated(), result.skipped());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/template")
    public ResponseEntity<byte[]> downloadTemplate() {
        String csvContent = String.join(",", ParentBulkImportService.TEMPLATE_HEADERS) + "\r\n";
        byte[] bytes = csvContent.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"parent_import_template.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(bytes);
    }

    /** Starter CSV pre-filled from Student.fatherName/motherName only — see
     *  ParentBulkImportService.buildPrefillCsv for exactly what is and isn't copied. */
    @GetMapping("/prefill")
    public ResponseEntity<byte[]> downloadPrefill() {
        byte[] bytes = parentBulkImportService.buildPrefillCsv();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"parent_import_prefill.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(bytes);
    }
}
