package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.FeatureCatalog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FeatureCatalogRepository extends JpaRepository<FeatureCatalog, String> {
    List<FeatureCatalog> findByIsAlwaysOnFalseOrderByCategoryAscDisplayNameAsc();
    List<FeatureCatalog> findAllByOrderByCategoryAscDisplayNameAsc();
}
