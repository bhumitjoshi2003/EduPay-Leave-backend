package com.indraacademy.ias_management.specification;

import com.indraacademy.ias_management.entity.SupportTicket;
import com.indraacademy.ias_management.entity.SupportTicketCategory;
import com.indraacademy.ias_management.entity.SupportTicketStatus;
import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.Predicate;

import java.util.ArrayList;
import java.util.List;

/** SUPER_ADMIN's global support queue filter — schoolId is included only when the caller wants
 *  to narrow to one school; SUPER_ADMIN otherwise sees every school's tickets (unlike every
 *  other Specification in this codebase, which is always school-scoped by its caller). */
public class SupportTicketSpecification {

    public static Specification<SupportTicket> filter(SupportTicketStatus status, SupportTicketCategory category, Long schoolId) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) predicates.add(cb.equal(root.get("status"), status));
            if (category != null) predicates.add(cb.equal(root.get("category"), category));
            if (schoolId != null) predicates.add(cb.equal(root.get("schoolId"), schoolId));
            query.orderBy(cb.desc(root.get("createdAt")));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
