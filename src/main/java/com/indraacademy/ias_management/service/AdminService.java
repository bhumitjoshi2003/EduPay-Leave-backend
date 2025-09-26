package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Admin;
import com.indraacademy.ias_management.repository.AdminRepository;
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
            Optional<Admin> admin = adminRepository.findById(adminId);
            if (admin.isPresent()) {
                log.info("Admin found with ID: {}", adminId);
            } else {
                log.warn("Admin not found with ID: {}", adminId);
            }
            return admin;
        } catch (DataAccessException e) {
            log.error("Database access error occurred while fetching admin ID: {}", adminId, e);
            throw new RuntimeException("Could not retrieve admin due to data access issue", e);
        }
    }

    public Admin createAdmin(Admin admin) {
        if (admin == null) {
            log.warn("Attempted to create a null admin object");
            return null;
        }
        log.info("Attempting to create a new admin with email: {}", admin.getEmail());
        try {
            Admin savedAdmin = adminRepository.save(admin);
            log.info("Admin created successfully with ID: {}", savedAdmin.getAdminId());
            return savedAdmin;
        } catch (DataAccessException e) {
            log.error("Database access error occurred while creating admin with email: {}", admin.getEmail(), e);
            throw new RuntimeException("Could not create admin due to data access issue", e);
        }
    }

    public Admin updateAdmin(String adminId, Admin admin) {
        if (adminId == null || adminId.trim().isEmpty() || admin == null) {
            log.warn("Attempted to update admin with null/empty ID or null admin object. ID: {}", adminId);
            return null;
        }
        log.info("Attempting to update admin with ID: {}", adminId);

        try {
            Optional<Admin> existingAdmin = adminRepository.findById(adminId);

            if (existingAdmin.isPresent()) {
                Admin adminToUpdate = existingAdmin.get();

                // Update fields
                adminToUpdate.setName(admin.getName());
                adminToUpdate.setEmail(admin.getEmail());
                adminToUpdate.setPhoneNumber(admin.getPhoneNumber());
                adminToUpdate.setDob(admin.getDob());
                adminToUpdate.setGender(admin.getGender());

                Admin updatedAdmin = adminRepository.save(adminToUpdate);
                log.info("Admin updated successfully with ID: {}", adminId);
                return updatedAdmin;
            } else {
                log.warn("Cannot update admin. Admin not found with ID: {}", adminId);
                return null;
            }
        } catch (DataAccessException e) {
            log.error("Database access error occurred while updating admin ID: {}", adminId, e);
            throw new RuntimeException("Could not update admin due to data access issue", e);
        }
    }

    public void deleteAdmin(String adminId) {
        if (adminId == null || adminId.trim().isEmpty()) {
            log.warn("Attempted to delete admin with null or empty ID");
            return;
        }
        log.info("Attempting to delete admin with ID: {}", adminId);

        try {
            if (adminRepository.existsById(adminId)) {
                adminRepository.deleteById(adminId);
                log.info("Admin deleted successfully with ID: {}", adminId);
            } else {
                log.warn("Cannot delete admin. Admin not found with ID: {}", adminId);
            }
        } catch (DataAccessException e) {
            log.error("Database access error occurred while deleting admin ID: {}", adminId, e);
            throw new RuntimeException("Could not delete admin due to data access issue", e);
        }
    }
}