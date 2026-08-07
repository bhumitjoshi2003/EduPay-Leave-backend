package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.KnowledgeDocumentDTO;
import com.indraacademy.ias_management.dto.KnowledgeSearchRequest;
import com.indraacademy.ias_management.dto.KnowledgeSearchResultDTO;
import com.indraacademy.ias_management.entity.KnowledgeDocument;
import com.indraacademy.ias_management.service.KnowledgeDocumentService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * Admin-managed school Knowledge Base for RAG. Upload/list/delete/download are
 * ADMIN-only; /search is called by the Python AI service (tools/knowledge_base.py)
 * forwarding the asking user's own accessToken cookie, so it runs under normal
 * authenticated-user + schoolId scoping like any other tool-backing endpoint —
 * every role can search, only ADMIN can manage documents.
 */
@RestController
@RequestMapping("/api/knowledge-base")
public class KnowledgeBaseController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseController.class);

    @Autowired private KnowledgeDocumentService knowledgeDocumentService;

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PostMapping
    public ResponseEntity<?> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("title") String title,
            @RequestParam(value = "category", required = false) String category) {
        try {
            KnowledgeDocumentDTO dto = knowledgeDocumentService.uploadDocument(file, title, category);
            return ResponseEntity.status(HttpStatus.CREATED).body(dto);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @GetMapping
    public ResponseEntity<List<KnowledgeDocumentDTO>> list() {
        return ResponseEntity.ok(knowledgeDocumentService.listDocuments());
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        try {
            knowledgeDocumentService.deleteDocument(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        KnowledgeDocument document;
        try {
            document = knowledgeDocumentService.getDocumentForDownload(id);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }

        try {
            byte[] content = Files.readAllBytes(Paths.get(document.getStoragePath()));
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + document.getOriginalFilename() + "\"")
                    .body(content);
        } catch (Exception e) {
            log.error("Failed to read knowledge base file for documentId={}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/search")
    public ResponseEntity<Map<String, List<KnowledgeSearchResultDTO>>> search(
            @Valid @RequestBody KnowledgeSearchRequest request) {
        List<KnowledgeSearchResultDTO> results =
                knowledgeDocumentService.search(request.getEmbedding(), request.getTopK());
        return ResponseEntity.ok(Map.of("results", results));
    }
}
