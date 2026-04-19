package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Admin;
import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.repository.AdminRepository;
import com.indraacademy.ias_management.repository.UserRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);
    private static final long MAX_PHOTO_SIZE = 10L * 1024 * 1024;

    @Value("${admin.photo.directory:./uploads/admin-photos}")
    private String photoDirectory;

    @Autowired private AdminRepository adminRepository;
    @Autowired private AuditService auditService;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    public List<Admin> getAllAdmins() {
        log.info("Attempting to fetch all admins");
        try {
            List<Admin> admins = adminRepository.findAll();
            log.info("Successfully fetched {} admins", admins.size());
            return admins;
        } catch (DataAccessException e) {
            log.error("Database access error occurred while fetching all admins", e);
            throw new RuntimeException("Could not retrieve admins due to data access issue", e);
        }
    }

    public Optional<Admin> getAdminById(String adminId) {
        if (adminId == null || adminId.trim().isEmpty()) {
            log.warn("Attempted to fetch admin with null or empty ID");
            return Optional.empty();
        }

        log.info("Attempting to fetch admin by ID: {}", adminId);

        try {
            return adminRepository.findById(adminId);
        } catch (DataAccessException e) {
            log.error("Database access error occurred while fetching admin ID: {}", adminId, e);
            throw new RuntimeException("Could not retrieve admin due to data access issue", e);
        }
    }

    @Transactional
    public Admin createAdmin(Admin admin, HttpServletRequest request) {

        if (admin == null) {
            log.warn("Attempted to create a null admin object");
            throw new IllegalArgumentException("Admin cannot be null");
        }

        log.info("Attempting to create a new admin with email: {}", admin.getEmail());

        try {
            Admin savedAdmin = adminRepository.save(admin);

            User newUser = new User();
            newUser.setUserId(savedAdmin.getAdminId());
            newUser.setEmail(savedAdmin.getEmail());
            newUser.setRole("ADMIN");
            newUser.setPassword(passwordEncoder.encode(admin.getDob().toString()));
            userRepository.save(newUser);

            auditService.log(
                    securityUtil.getUsername(),
                    securityUtil.getRole(),
                    "CREATE_ADMIN",
                    "Admin",
                    savedAdmin.getAdminId(),
                    null,
                    objectMapper.writeValueAsString(savedAdmin),
                    request.getRemoteAddr()
            );

            log.info("Admin created successfully with ID: {}", savedAdmin.getAdminId());
            return savedAdmin;

        } catch (DataAccessException e) {
            log.error("Database access error occurred while creating admin with email: {}", admin.getEmail(), e);
            throw new RuntimeException("Could not create admin due to data access issue", e);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public Admin updateAdmin(String adminId, Admin admin, HttpServletRequest request) {

        if (adminId == null || adminId.trim().isEmpty() || admin == null) {
            log.warn("Invalid update request for admin. ID: {}", adminId);
            throw new IllegalArgumentException("Invalid admin update request");
        }

        String loggedInUserId = securityUtil.getUsername();
        String loggedInUserRole = securityUtil.getRole();

        log.info("Attempting to update admin with ID: {}", adminId);

        if (!"SUPER_ADMIN".equals(loggedInUserRole)) {
            if (!loggedInUserId.equals(adminId)) {
                log.warn("Access Denied: Admin {} tried to edit Admin {}", loggedInUserId, adminId);
                throw new RuntimeException("Access Denied: You can only edit your own profile.");
            }
        }

        try {
            Admin existingAdmin = adminRepository.findById(adminId)
                    .orElseThrow(() -> new RuntimeException("Admin not found"));

            String oldValue = objectMapper.writeValueAsString(existingAdmin);

            existingAdmin.setName(admin.getName());
            existingAdmin.setEmail(admin.getEmail());
            existingAdmin.setPhoneNumber(admin.getPhoneNumber());
            existingAdmin.setDob(admin.getDob());
            existingAdmin.setGender(admin.getGender());

            Admin updatedAdmin = adminRepository.save(existingAdmin);

            auditService.log(
                    securityUtil.getUsername(),
                    securityUtil.getRole(),
                    "UPDATE_ADMIN",
                    "Admin",
                    adminId,
                    oldValue,
                    objectMapper.writeValueAsString(updatedAdmin),
                    request.getRemoteAddr()
            );

            log.info("Admin updated successfully with ID: {}", adminId);
            return updatedAdmin;

        } catch (DataAccessException e) {
            log.error("Database access error occurred while updating admin ID: {}", adminId, e);
            throw new RuntimeException("Could not update admin due to data access issue", e);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional
    public void deleteAdmin(String adminId, HttpServletRequest request) {

        if (adminId == null || adminId.trim().isEmpty()) {
            log.warn("Attempted to delete admin with null or empty ID");
            throw new IllegalArgumentException("Invalid admin ID");
        }

        log.info("Attempting to delete admin with ID: {}", adminId);

        try {
            Admin existingAdmin = adminRepository.findById(adminId)
                    .orElseThrow(() -> new RuntimeException("Admin not found"));

            String oldValue = objectMapper.writeValueAsString(existingAdmin);

            adminRepository.deleteById(adminId);

            userRepository.findByUserId(adminId).ifPresent(user -> {
                userRepository.delete(user);
                log.info("Deleted corresponding user credentials for ID: {}", adminId);
            });

            auditService.log(
                    securityUtil.getUsername(),
                    securityUtil.getRole(),
                    "DELETE_ADMIN",
                    "Admin",
                    adminId,
                    oldValue,
                    null,
                    request.getRemoteAddr()
            );

            log.info("Admin deleted successfully with ID: {}", adminId);

        } catch (DataAccessException e) {
            log.error("Database access error occurred while deleting admin ID: {}", adminId, e);
            throw new RuntimeException("Could not delete admin due to data access issue", e);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional
    public String uploadPhoto(String adminId, MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("Only image files are allowed.");
        }
        if (file.getSize() > MAX_PHOTO_SIZE) {
            throw new IllegalArgumentException("File size exceeds the 10 MB limit.");
        }

        Admin admin = adminRepository.findById(adminId)
                .orElseThrow(() -> new NoSuchElementException("Admin not found: " + adminId));

        try {
            Path storageDir = Paths.get(photoDirectory).toAbsolutePath().normalize();
            Files.createDirectories(storageDir);

            String fileName = adminId + ".jpg";
            Path targetLocation = storageDir.resolve(fileName);
            Thumbnails.of(file.getInputStream())
                    .size(400, 400)
                    .keepAspectRatio(true)
                    .outputFormat("jpg")
                    .outputQuality(0.80)
                    .toFile(targetLocation.toFile());

            String relativeUrl = "/uploads/admin-photos/" + fileName;
            admin.setPhotoUrl(relativeUrl);
            adminRepository.save(admin);

            log.info("Photo uploaded and resized for admin {}: {}", adminId, relativeUrl);
            return relativeUrl;
        } catch (IOException e) {
            log.error("Failed to store photo for admin {}", adminId, e);
            throw new RuntimeException("Could not store photo for admin " + adminId, e);
        }
    }
}