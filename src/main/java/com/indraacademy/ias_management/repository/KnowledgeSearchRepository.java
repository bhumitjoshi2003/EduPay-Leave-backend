package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.dto.KnowledgeSearchResultDTO;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written native SQL for the pgvector `embedding` column on knowledge_chunk,
 * which is deliberately NOT mapped by JPA/Hibernate — this codebase has no custom
 * vector UserType (see KnowledgeChunk's class comment). Every method here either
 * writes an embedding via a `?::vector` string-cast parameter, or reads back only
 * text/ids/distance — the vector value itself is never deserialized into Java.
 *
 * Requires a one-time manual step against prod Postgres (ddl-auto=update creates
 * the rest of the table but can't create a custom column type):
 *   CREATE EXTENSION IF NOT EXISTS vector;
 *   ALTER TABLE knowledge_chunk ADD COLUMN embedding vector(1536);
 */
@Repository
public class KnowledgeSearchRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public void saveEmbedding(Long chunkId, List<Float> embedding) {
        entityManager.createNativeQuery(
                        "UPDATE knowledge_chunk SET embedding = ?1::vector WHERE id = ?2")
                .setParameter(1, toVectorLiteral(embedding))
                .setParameter(2, chunkId)
                .executeUpdate();
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public List<KnowledgeSearchResultDTO> search(Long schoolId, List<Float> queryEmbedding, int topK) {
        Query query = entityManager.createNativeQuery(
                "SELECT c.chunk_text, c.document_id, d.title, (c.embedding <=> ?1::vector) AS distance " +
                "FROM knowledge_chunk c " +
                "JOIN knowledge_document d ON d.id = c.document_id " +
                "WHERE c.school_id = ?2 AND d.status = 'READY' AND c.embedding IS NOT NULL " +
                "ORDER BY distance ASC " +
                "LIMIT ?3");
        query.setParameter(1, toVectorLiteral(queryEmbedding));
        query.setParameter(2, schoolId);
        query.setParameter(3, topK);

        List<Object[]> rows = query.getResultList();
        List<KnowledgeSearchResultDTO> results = new ArrayList<>();
        for (Object[] row : rows) {
            String chunkText = (String) row[0];
            Long documentId = ((Number) row[1]).longValue();
            String documentTitle = (String) row[2];
            double distance = ((Number) row[3]).doubleValue();
            // pgvector's <=> is cosine distance (1 - cosine similarity) — invert back
            // to a similarity score so higher always means "more relevant".
            double similarity = 1.0 - distance;
            results.add(new KnowledgeSearchResultDTO(chunkText, documentId, documentTitle, similarity));
        }
        return results;
    }

    private String toVectorLiteral(List<Float> embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(embedding.get(i));
        }
        sb.append(']');
        return sb.toString();
    }
}
