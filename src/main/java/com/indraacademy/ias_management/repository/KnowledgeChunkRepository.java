package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.KnowledgeChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {

    @Modifying
    @Query("DELETE FROM KnowledgeChunk c WHERE c.documentId = :documentId")
    void deleteByDocumentId(@Param("documentId") Long documentId);
}
