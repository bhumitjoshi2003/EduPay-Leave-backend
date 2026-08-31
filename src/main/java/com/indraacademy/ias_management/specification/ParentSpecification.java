package com.indraacademy.ias_management.specification;

import com.indraacademy.ias_management.dto.ParentFilterDTO;
import com.indraacademy.ias_management.entity.Parent;
import com.indraacademy.ias_management.entity.ParentStudentRelationship;
import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

import java.util.ArrayList;
import java.util.List;

public class ParentSpecification {

    public static Specification<Parent> filter(ParentFilterDTO filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Always required — never trust a client-supplied schoolId, ParentPortalService sets this.
            predicates.add(cb.equal(root.get("schoolId"), filter.getSchoolId()));

            if (filter.getSearch() != null && !filter.getSearch().trim().isEmpty()) {
                String like = "%" + filter.getSearch().trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(root.get("parentId")), like),
                        cb.like(root.get("phoneNumber"), like),
                        cb.like(cb.lower(cb.coalesce(root.get("email"), "")), like)
                ));
            }

            if ("ACTIVE".equalsIgnoreCase(filter.getStatus())) {
                predicates.add(cb.isTrue(root.get("active")));
            } else if ("DISABLED".equalsIgnoreCase(filter.getStatus())) {
                predicates.add(cb.isFalse(root.get("active")));
            }

            if ("LINKED".equalsIgnoreCase(filter.getLinked()) || "UNLINKED".equalsIgnoreCase(filter.getLinked())) {
                predicates.add(linkedPredicate(root, query, cb, filter));
            }

            query.orderBy(cb.asc(root.get("name")));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static Predicate linkedPredicate(Root<Parent> root, jakarta.persistence.criteria.CriteriaQuery<?> query,
                                             jakarta.persistence.criteria.CriteriaBuilder cb, ParentFilterDTO filter) {
        Subquery<Long> subquery = query.subquery(Long.class);
        Root<ParentStudentRelationship> relationship = subquery.from(ParentStudentRelationship.class);
        subquery.select(cb.literal(1L)).where(cb.and(
                cb.equal(relationship.get("schoolId"), filter.getSchoolId()),
                cb.equal(relationship.get("parentId"), root.get("parentId")),
                cb.isTrue(relationship.get("active"))
        ));
        Predicate exists = cb.exists(subquery);
        return "LINKED".equalsIgnoreCase(filter.getLinked()) ? exists : cb.not(exists);
    }
}
