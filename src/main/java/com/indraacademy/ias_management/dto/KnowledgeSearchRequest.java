package com.indraacademy.ias_management.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Body for POST /api/knowledge-base/search — called by the Python AI service
 * (see edunexify-ai/tools/knowledge_base.py), forwarding the user's own accessToken
 * cookie, so this endpoint runs under the same auth/schoolId-scoping as any other. */
public class KnowledgeSearchRequest {

    @NotEmpty(message = "embedding is required")
    private List<Float> embedding;

    @NotNull(message = "topK is required")
    private Integer topK;

    public List<Float> getEmbedding() { return embedding; }
    public void setEmbedding(List<Float> embedding) { this.embedding = embedding; }

    public Integer getTopK() { return topK; }
    public void setTopK(Integer topK) { this.topK = topK; }
}
