package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.config.FileStorageProperties;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.UUID;

@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    private final Path fileStorageLocation;

    @Value("${app.backend-url:http://localhost:8080}")
    private String backendUrl;

    @Autowired
    public FileStorageService(FileStorageProperties fileStorageProperties) {
        this.fileStorageLocation = Paths.get(fileStorageProperties.getDirectory())
                .toAbsolutePath().normalize();

        log.info("Initializing file storage directory: {}", this.fileStorageLocation);

        try {
            Files.createDirectories(this.fileStorageLocation);
            log.info("File storage directory created or already exists.");
        } catch (IOException ex) {
            log.error("Could not create the directory where the uploaded files will be stored: {}", this.fileStorageLocation, ex);
            throw new RuntimeException("Could not create the directory where the uploaded files will be stored.", ex);
        }
    }

    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024; // 10 MB

    public String storeFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            log.warn("Attempted to store a null or empty file.");
            throw new IllegalArgumentException("Cannot store a null or empty file.");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("Only image files are allowed.");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("File size exceeds the 10 MB limit.");
        }

        String originalFilename = StringUtils.cleanPath(Objects.requireNonNull(file.getOriginalFilename()));
        log.info("Attempting to store event image: {}", originalFilename);

        // Always output as .jpg
        String fileName = UUID.randomUUID().toString() + ".jpg";

        try {
            Path targetLocation = this.fileStorageLocation.resolve(fileName);
            Thumbnails.of(file.getInputStream())
                    .size(1200, 800)
                    .keepAspectRatio(true)
                    .outputFormat("jpg")
                    .outputQuality(0.85)
                    .toFile(targetLocation.toFile());

            String relativePath = "/uploads/events/images/" + fileName;
            log.info("Event image stored and resized. Name: {}, path: {}", fileName, relativePath);
            return relativePath;
        } catch (IOException ex) {
            log.error("Could not store event image: {}", originalFilename, ex);
            throw new RuntimeException("Could not store file " + originalFilename + ". Please try again!", ex);
        }
    }

    public Resource loadFileAsResource(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            log.warn("Attempted to load file with null or empty file name.");
            throw new IllegalArgumentException("File name must be provided.");
        }

        log.info("Attempting to load file as resource: {}", fileName);

        try {
            Path filePath = this.fileStorageLocation.resolve(fileName).normalize();
            Resource resource = new UrlResource(filePath.toUri());

            if (resource.exists()) {
                log.info("Resource found for file: {}", fileName);
                return resource;
            } else {
                log.warn("File not found at path: {}", filePath);
                throw new FileNotFoundException("File not found " + fileName);
            }
        } catch (MalformedURLException ex) {
            log.error("Malformed URL exception for file: {}", fileName, ex);
            throw new RuntimeException("File not found " + fileName, ex);
        } catch (FileNotFoundException ex) {
            throw new RuntimeException(ex.getMessage(), ex);
        } catch (Exception ex) {
            log.error("Unexpected error loading file as resource: {}", fileName, ex);
            throw new RuntimeException("Unexpected error loading file as resource.", ex);
        }
    }
}