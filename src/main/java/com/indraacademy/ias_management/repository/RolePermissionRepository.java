package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.RolePermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RolePermissionRepository extends JpaRepository<RolePermission, Long> {
    List<RolePermission> findByRole(String role);

    @Query("SELECT rp FROM RolePermission rp WHERE rp.role = :role AND (rp.schoolId IS NULL OR rp.schoolId = :schoolId)")
    List<RolePermission> findByRoleAndSchool(@Param("role") String role, @Param("schoolId") Long schoolId);

    List<RolePermission> findBySchoolIdIsNull();

    @Query("SELECT DISTINCT rp.permission.permissionKey FROM RolePermission rp WHERE rp.role = :role AND (rp.schoolId IS NULL OR rp.schoolId = :schoolId)")
    List<String> findPermissionKeysByRoleAndSchool(@Param("role") String role, @Param("schoolId") Long schoolId);
}
