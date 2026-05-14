package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.FeatureCatalogResponse;
import com.indraacademy.ias_management.dto.PlanResponse;
import com.indraacademy.ias_management.dto.PublicSchoolResponse;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.service.PlanService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/public")
public class PublicSchoolController {

    @Autowired
    private SchoolRepository schoolRepository;

    @Autowired
    private PlanService planService;

    /**
     * Public unauthenticated endpoint — called by the frontend on load to get
     * school branding for the login screen.
     * Returns only safe, non-sensitive fields. Returns 404 if the slug is unknown
     * or the school is inactive (prevents probing for valid slugs of inactive schools).
     */
    @GetMapping("/school/{slug}")
    public ResponseEntity<PublicSchoolResponse> getSchoolBySlug(@PathVariable String slug) {
        return schoolRepository.findBySlug(slug.toLowerCase())
                .filter(School::isActive)
                .map(school -> {
                    String boardType = school.getBoardType() != null
                            ? school.getBoardType().name()
                            : null;
                    PublicSchoolResponse response = new PublicSchoolResponse(
                            school.getName(),
                            school.getSlug(),
                            school.getLogoUrl(),
                            school.getThemeColor(),
                            school.getCity(),
                            boardType
                    );
                    return ResponseEntity.ok(response);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Public unauthenticated endpoint — returns all active, publicly visible plans.
     * Used by the pricing page at /plans.
     */
    @GetMapping("/plans")
    public List<PlanResponse> getPublicPlans() {
        return planService.getAllPlans(false).stream()
                .filter(PlanResponse::isPublic)
                .toList();
    }
}
