package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Admin;
import com.indraacademy.ias_management.repository.AdminRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;

@Service
public class AdminService {

    @Autowired
    private AdminRepository adminRepository;

    public List<Admin> getAllAdmins() {
        return adminRepository.findAll();
    }

    public Optional<Admin> getAdminById(String adminId) {
        return adminRepository.findById(adminId);
    }

    public Admin createAdmin(Admin admin) {
        return adminRepository.save(admin);
    }

    public Admin updateAdmin(String adminId, Admin admin) {
        Optional<Admin> existingAdmin = adminRepository.findById(adminId);
        if (existingAdmin.isPresent()) {
            Admin adminToUpdate = existingAdmin.get();
            adminToUpdate.setName(admin.getName());
            adminToUpdate.setEmail(admin.getEmail());
            adminToUpdate.setPhoneNumber(admin.getPhoneNumber());
            adminToUpdate.setDob(admin.getDob());
            adminToUpdate.setGender(admin.getGender());
            return adminRepository.save(adminToUpdate);
        } else {
            return null;
        }
    }

    public void deleteAdmin(String adminId) {
        adminRepository.deleteById(adminId);
    }
}
