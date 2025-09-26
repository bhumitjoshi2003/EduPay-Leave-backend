package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.config.FileStorageProperties;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.UUID;

@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    private final Path fileStorageLocation;

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

    public String storeFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            log.warn("Attempted to store a null or empty file.");
            throw new IllegalArgumentException("Cannot store a null or empty file.");
        }

        String originalFilename = StringUtils.cleanPath(Objects.requireNonNull(file.getOriginalFilename()));
        log.info("Attempting to store file: {}", originalFilename);

        String fileExtension = "";
        int dotIndex = originalFilename.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < originalFilename.length() - 1) {
            fileExtension = originalFilename.substring(dotIndex);
        }

        String fileName = UUID.randomUUID().toString() + fileExtension;

        try {
            if (fileName.contains("..")) {
                log.error("Security risk detected: Filename contains invalid path sequence: {}", fileName);
                throw new IOException("Filename contains invalid path sequence " + fileName);
            }

            Path targetLocation = this.fileStorageLocation.resolve(fileName);
            Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);

            String relativePath = "/uploads/events/images/" + fileName;
            log.info("File stored successfully. Unique name: {}, Relative path: {}", fileName, relativePath);
            return relativePath;
        } catch (IOException ex) {
            log.error("Could not store file {}. Target location: {}", originalFilename, this.fileStorageLocation.resolve(fileName), ex);
            throw new RuntimeException("Could not store file " + originalFilename + ". Please try again!", ex);
        } catch (Exception ex) {
            log.error("Unexpected error during file storage for: {}", originalFilename, ex);
            throw new RuntimeException("Unexpected error during file storage.", ex);
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