package com.indraacademy.ias_management.specification;

import com.indraacademy.ias_management.dto.AuditFilterDTO;
import com.indraacademy.ias_management.entity.AuditLog;
import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Expression;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class AuditSpecification {

    public static Specification<AuditLog> filter(AuditFilterDTO filter) {

        return (root, query, cb) -> {

            List<Predicate> predicates = new ArrayList<>();

            // Typed timestamp expression (explicit) to avoid generic inference issues
            Expression<LocalDateTime> timestamp = root.get("timestamp").as(LocalDateTime.class);

            // 1. Username filter
            if (filter.getUsername() != null && !filter.getUsername().trim().isEmpty()) {
                predicates.add(cb.equal(root.get("username"), filter.getUsername()));
            }

            // 2. Role filter
            if (filter.getRole() != null && !filter.getRole().trim().isEmpty()) {
                predicates.add(cb.equal(root.get("role"), filter.getRole()));
            }

            // 3. Entity Name filter
            if (filter.getEntityName() != null && !filter.getEntityName().trim().isEmpty()) {
                predicates.add(cb.equal(root.get("entityName"), filter.getEntityName()));
            }

            // 4. Action filter
            if (filter.getAction() != null && !filter.getAction().trim().isEmpty()) {
                predicates.add(cb.equal(root.get("action"), filter.getAction()));
            }

            // 5. Start Date filter
            if (filter.getStartDate() != null) {
                predicates.add(
                        cb.greaterThanOrEqualTo(
                                timestamp,
                                filter.getStartDate().atStartOfDay()
                        )
                );
            }

            // 6. End Date filter (production-safe)
            if (filter.getEndDate() != null) {
                predicates.add(
                        cb.lessThan(
                                timestamp,
                                filter.getEndDate().plusDays(1).atStartOfDay()
                        )
                );
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}