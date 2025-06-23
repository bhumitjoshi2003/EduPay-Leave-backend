package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.service.FileStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/api/files") // A dedicated path for file operations
@CrossOrigin(origins = "http://192.168.2.43:4200")
public class FileUploadController {

    @Autowired
    private FileStorageService fileStorageService;

    @PostMapping("/uploadEventImage")
    public ResponseEntity<Map<String, String>> uploadEventImage(@RequestParam("file") MultipartFile file) {
        try {
            String fileDownloadUri = fileStorageService.storeFile(file);
            return ResponseEntity.ok(Collections.singletonMap("imageUrl", fileDownloadUri));
        } catch (RuntimeException ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Collections.singletonMap("error", ex.getMessage()));
        }
    }

    // Endpoint to serve images
    @GetMapping("/event-images/{filename:.+}") // Regex to allow dot in filename
    public ResponseEntity<Resource> downloadEventImage(@PathVariable String filename, HttpServletRequest request) {
        Resource resource = null;
        try {
            resource = fileStorageService.loadFileAsResource(filename);
        } catch (RuntimeException ex) {
            // Log the exception, return 404 or other appropriate error
            return ResponseEntity.notFound().build();
        }

        // Try to determine file's content type
        String contentType = null;
        try {
            contentType = request.getServletContext().getMimeType(resource.getFile().getAbsolutePath());
        } catch (IOException ex) {
            // Fallback to the default content type if type could not be determined
            System.out.println("Could not determine file type.");
        }

        // Fallback to default content type if not determined
        if(contentType == null) {
            contentType = "application/octet-stream";
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + resource.getFilename() + "\"") // Use inline to display in browser
                .body(resource);
    }
}