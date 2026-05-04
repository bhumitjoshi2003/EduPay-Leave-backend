package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.Admin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AdminRepository extends JpaRepository<Admin, String> {

    List<Admin> findBySchoolId(Long schoolId);

    Optional<Admin> findByAdminIdAndSchoolId(String adminId, Long schoolId);
}
