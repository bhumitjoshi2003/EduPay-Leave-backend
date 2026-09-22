package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.StaffAdoptionResponse;
import com.indraacademy.ias_management.service.StaffAdoptionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/staff-adoption")
@PreAuthorize("hasRole('" + Role.ADMIN + "')")
public class StaffAdoptionController {
    private final StaffAdoptionService service;

    public StaffAdoptionController(StaffAdoptionService service) {
        this.service = service;
    }

    @GetMapping
    public StaffAdoptionResponse getStaffAdoption() {
        return service.getStaffAdoption();
    }
}
