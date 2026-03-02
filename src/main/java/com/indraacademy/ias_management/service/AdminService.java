package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Admin;
import com.indraacademy.ias_management.repository.AdminRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    @Autowired
    private AdminRepository adminRepository;

    @Autowired
    private AuditService auditService;

    @Autowired
    private SecurityUtil securityUtil;

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

    public Admin createAdmin(Admin admin, HttpServletRequest request) {

        if (admin == null) {
            log.warn("Attempted to create a null admin object");
            throw new IllegalArgumentException("Admin cannot be null");
        }

        log.info("Attempting to create a new admin with email: {}", admin.getEmail());

        try {
            Admin savedAdmin = adminRepository.save(admin);

            auditService.log(
                    securityUtil.getUsername(),
                    securityUtil.getRole(),
                    "CREATE_ADMIN",
                    "Admin",
                    savedAdmin.getAdminId(),
                    null,
                    savedAdmin.toString(),
                    request.getRemoteAddr()
            );

            log.info("Admin created successfully with ID: {}", savedAdmin.getAdminId());
            return savedAdmin;

        } catch (DataAccessException e) {
            log.error("Database access error occurred while creating admin with email: {}", admin.getEmail(), e);
            throw new RuntimeException("Could not create admin due to data access issue", e);
        }
    }

    public Admin updateAdmin(String adminId, Admin admin, HttpServletRequest request) {

        if (adminId == null || adminId.trim().isEmpty() || admin == null) {
            log.warn("Invalid update request for admin. ID: {}", adminId);
            throw new IllegalArgumentException("Invalid admin update request");
        }

        log.info("Attempting to update admin with ID: {}", adminId);

        try {
            Admin existingAdmin = adminRepository.findById(adminId)
                    .orElseThrow(() -> new RuntimeException("Admin not found"));

            String oldValue = existingAdmin.toString();

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
                    updatedAdmin.toString(),
                    request.getRemoteAddr()
            );

            log.info("Admin updated successfully with ID: {}", adminId);
            return updatedAdmin;

        } catch (DataAccessException e) {
            log.error("Database access error occurred while updating admin ID: {}", adminId, e);
            throw new RuntimeException("Could not update admin due to data access issue", e);
        }
    }

    public void deleteAdmin(String adminId, HttpServletRequest request) {

        if (adminId == null || adminId.trim().isEmpty()) {
            log.warn("Attempted to delete admin with null or empty ID");
            throw new IllegalArgumentException("Invalid admin ID");
        }

        log.info("Attempting to delete admin with ID: {}", adminId);

        try {
            Admin existingAdmin = adminRepository.findById(adminId)
                    .orElseThrow(() -> new RuntimeException("Admin not found"));

            String oldValue = existingAdmin.toString();

            adminRepository.deleteById(adminId);

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
        }
    }
}