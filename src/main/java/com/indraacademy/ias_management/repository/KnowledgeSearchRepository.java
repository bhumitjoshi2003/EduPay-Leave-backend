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
 * writes an embedding via a CAST(?N AS vector) parameter, or reads back only
 * text/ids/distance — the vector value itself is never deserialized into Java.
 *
 * Uses CAST(... AS vector) rather than the shorter `?N::vector` Postgres cast
 * syntax deliberately: Hibernate's native-query parameter scanner treats `:` as
 * the start of a named parameter (`:name`), so `?1::vector` gets misparsed — the
 * `:vector` half looks like a named parameter — and throws
 * "Ordinal parameter label was not an integer" at runtime. CAST(...) has no colon.
 *
 * The `CREATE EXTENSION vector` + `ALTER TABLE ... ADD COLUMN embedding vector(1536)`
 * that ddl-auto=update can't do on its own runs automatically on every startup —
 * see KnowledgeBaseSchemaInitializer in the config package.
 */
@Repository
public class KnowledgeSearchRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public void saveEmbedding(Long chunkId, List<Float> embedding) {
        entityManager.createNativeQuery(
                        "UPDATE knowledge_chunk SET embedding = CAST(?1 AS vector) WHERE id = ?2")
                .setParameter(1, toVectorLiteral(embedding))
                .setParameter(2, chunkId)
                .executeUpdate();
    }

    /**
     * minSimilarity is a relevance floor, not just a UI nicety: without it, a query with
     * no real answer in the knowledge base still gets topK chunks back (whatever's
     * closest, however unrelated), and the LLM has no reliable signal to distinguish
     * "here's your answer" from "here's the least-irrelevant thing I could find" —
     * which is exactly how it ends up inventing an answer instead of saying it doesn't
     * know. Chunks below the floor are excluded from the result set entirely (not just
     * scored low), so a query with nothing relevant enough correctly comes back empty.
     */
    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public List<KnowledgeSearchResultDTO> search(Long schoolId, List<Float> queryEmbedding, int topK, double minSimilarity) {
        double maxDistance = 1.0 - minSimilarity;
        Query query = entityManager.createNativeQuery(
                "SELECT c.chunk_text, c.document_id, d.title, c.page_number, " +
                "(c.embedding <=> CAST(?1 AS vector)) AS distance " +
                "FROM knowledge_chunk c " +
                "JOIN knowledge_document d ON d.id = c.document_id " +
                "WHERE c.school_id = ?2 AND d.status = 'READY' AND c.embedding IS NOT NULL " +
                "AND (c.embedding <=> CAST(?1 AS vector)) <= ?4 " +
                "ORDER BY distance ASC " +
                "LIMIT ?3");
        query.setParameter(1, toVectorLiteral(queryEmbedding));
        query.setParameter(2, schoolId);
        query.setParameter(3, topK);
        query.setParameter(4, maxDistance);

        List<Object[]> rows = query.getResultList();
        List<KnowledgeSearchResultDTO> results = new ArrayList<>();
        for (Object[] row : rows) {
            String chunkText = (String) row[0];
            Long documentId = ((Number) row[1]).longValue();
            String documentTitle = (String) row[2];
            Integer pageNumber = row[3] != null ? ((Number) row[3]).intValue() : null;
            double distance = ((Number) row[4]).doubleValue();
            // pgvector's <=> is cosine distance (1 - cosine similarity) — invert back
            // to a similarity score so higher always means "more relevant".
            double similarity = 1.0 - distance;
            results.add(new KnowledgeSearchResultDTO(chunkText, documentId, documentTitle, pageNumber, similarity));
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
