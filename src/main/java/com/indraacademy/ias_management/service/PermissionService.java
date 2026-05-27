package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.PermissionDTO;
import com.indraacademy.ias_management.dto.RolePermissionDTO;
import com.indraacademy.ias_management.entity.Permission;
import com.indraacademy.ias_management.repository.PermissionRepository;
import com.indraacademy.ias_management.repository.RolePermissionRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class PermissionService {

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private RolePermissionRepository rolePermissionRepository;

    @Autowired
    private SecurityUtil securityUtil;

    public List<PermissionDTO> getAllPermissions() {
        return permissionRepository.findAllByOrderByCategoryAscPermissionKeyAsc()
                .stream().map(this::toDTO).collect(Collectors.toList());
    }

    public List<RolePermissionDTO> getRolePermissionMatrix() {
        Long schoolId = securityUtil.getSchoolId();
        String[] roles = {"ADMIN", "TEACHER", "STUDENT", "SUB_ADMIN"};
        return Arrays.stream(roles)
                .map(role -> {
                    List<String> keys = rolePermissionRepository.findPermissionKeysByRoleAndSchool(role, schoolId);
                    return new RolePermissionDTO(role, keys);
                })
                .collect(Collectors.toList());
    }

    public List<String> getPermissionKeysForRole(String role, Long schoolId) {
        return rolePermissionRepository.findPermissionKeysByRoleAndSchool(role, schoolId);
    }

    private PermissionDTO toDTO(Permission p) {
        return new PermissionDTO(p.getPermissionKey(), p.getDisplayName(), p.getCategory(), p.getDescription());
    }
}
