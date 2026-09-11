package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.WisdomDtos.VerseImportRecord;
import com.indraacademy.ias_management.dto.WisdomDtos.VerseImportSummary;
import com.indraacademy.ias_management.service.WisdomVerseImportService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Deliberate, super-admin-only ingestion of a reviewed scripture dataset. Never invoked
 * automatically — a human must submit the already-reviewed records themselves, sourced from a
 * version-controlled import artifact. See {@link WisdomVerseImportService} for the guarantees.
 */
@RestController
@RequestMapping("/api/super-admin/wisdom/verses")
@PreAuthorize("hasRole('"+Role.SUPER_ADMIN+"')")
public class WisdomVerseImportController {
 private final WisdomVerseImportService importService;
 public WisdomVerseImportController(WisdomVerseImportService importService) { this.importService = importService; }

 @PostMapping("/import")
 public VerseImportSummary importVerses(@Valid @RequestBody List<VerseImportRecord> records) {
  return importService.importVerified(records);
 }
}
