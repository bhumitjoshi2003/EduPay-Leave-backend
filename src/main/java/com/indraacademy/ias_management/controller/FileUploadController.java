package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.service.FileStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/api/files")
@CrossOrigin(origins = "http://192.168.2.43:4200")
public class FileUploadController {

    private static final Logger log = LoggerFactory.getLogger(FileUploadController.class);

    @Autowired
    private FileStorageService fileStorageService;

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "')")
    @PostMapping("/uploadEventImage")
    public ResponseEntity<Map<String, String>> uploadEventImage(@RequestParam("file") MultipartFile file) {
        log.info("Request to upload event image: {}", file.getOriginalFilename());
        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Collections.singletonMap("error", "The uploaded file is empty."));
        }
        String fileDownloadUri = fileStorageService.storeFile(file);
        log.info("File stored successfully, URI: {}", fileDownloadUri);
        return ResponseEntity.ok(Collections.singletonMap("imageUrl", fileDownloadUri));
    }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "')")
    @GetMapping("/event-images/{filename:.+}")
    public ResponseEntity<Resource> downloadEventImage(@PathVariable String filename, HttpServletRequest request) {
        log.info("Request to download event image: {}", filename);
        Resource resource = fileStorageService.loadFileAsResource(filename);

        String contentType = null;
        try {
            contentType = request.getServletContext().getMimeType(resource.getFile().getAbsolutePath());
        } catch (IOException ex) {
            log.debug("Could not determine file type for {}.", filename, ex);
        }

        // Fallback to default content type if not determined
        if(contentType == null) {
            contentType = "application/octet-stream";
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + resource.getFilename() + "\"")
                .body(resource);
    }
}