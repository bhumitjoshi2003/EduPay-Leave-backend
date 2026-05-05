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

            Expression<LocalDateTime> timestamp = root.get("timestamp").as(LocalDateTime.class);

            if (filter.getUsername() != null && !filter.getUsername().trim().isEmpty()) {
                predicates.add(cb.equal(root.get("username"), filter.getUsername()));
            }

            if (filter.getRole() != null && !filter.getRole().trim().isEmpty()) {
                predicates.add(cb.equal(root.get("role"), filter.getRole()));
            }

            if (filter.getEntityName() != null && !filter.getEntityName().trim().isEmpty()) {
                predicates.add(cb.equal(root.get("entityName"), filter.getEntityName()));
            }

            if (filter.getAction() != null && !filter.getAction().trim().isEmpty()) {
                predicates.add(cb.equal(root.get("action"), filter.getAction()));
            }

            if (filter.getStartDate() != null) {
                predicates.add(
                        cb.greaterThanOrEqualTo(
                                timestamp,
                                filter.getStartDate().atStartOfDay()
                        )
                );
            }

            if (filter.getEndDate() != null) {
                predicates.add(
                        cb.lessThan(
                                timestamp,
                                filter.getEndDate().plusDays(1).atStartOfDay()
                        )
                );
            }

            if (filter.getSchoolId() != null) {
                predicates.add(cb.equal(root.get("schoolId"), filter.getSchoolId()));
            }

            query.orderBy(cb.desc(root.get("timestamp")));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}