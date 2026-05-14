package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.SchoolEffectiveEntitlement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SchoolEffectiveEntitlementRepository extends JpaRepository<SchoolEffectiveEntitlement, Long> {
}
