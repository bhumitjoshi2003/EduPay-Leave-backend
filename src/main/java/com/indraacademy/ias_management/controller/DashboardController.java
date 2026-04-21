package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.ClassStatsDto;
import com.indraacademy.ias_management.dto.DashboardStatsDto;
import com.indraacademy.ias_management.dto.FeeTrendDto;
import com.indraacademy.ias_management.service.DashboardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/dashboard")
@PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "')")
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    @Autowired private DashboardService dashboardService;

    @GetMapping("/stats")
    public ResponseEntity<?> getStats() {
        log.info("GET /api/dashboard/stats");
        try {
            DashboardStatsDto stats = dashboardService.getStats();
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            log.error("Error fetching dashboard stats", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to fetch dashboard stats.");
        }
    }

    @GetMapping("/fee-trend")
    public ResponseEntity<?> getFeeTrend() {
        log.info("GET /api/dashboard/fee-trend");
        try {
            List<FeeTrendDto> trend = dashboardService.getFeeTrend();
            return ResponseEntity.ok(trend);
        } catch (Exception e) {
            log.error("Error fetching fee trend", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to fetch fee trend.");
        }
    }

    @GetMapping("/class-stats")
    public ResponseEntity<?> getClassStats() {
        log.info("GET /api/dashboard/class-stats");
        try {
            List<ClassStatsDto> stats = dashboardService.getClassStats();
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            log.error("Error fetching class stats", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to fetch class stats.");
        }
    }
}
