package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PermissionRepository extends JpaRepository<Permission, String> {
    List<Permission> findAllByOrderByCategoryAscPermissionKeyAsc();
}
