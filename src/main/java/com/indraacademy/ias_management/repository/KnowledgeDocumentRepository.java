package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.KnowledgeDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {

    List<KnowledgeDocument> findBySchoolIdOrderByCreatedAtDesc(Long schoolId);

    Optional<KnowledgeDocument> findByIdAndSchoolId(Long id, Long schoolId);
}
