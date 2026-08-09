package com.indraacademy.ias_management.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

/**
 * Body for POST /api/ai/trace. Sent by the Python AI service (tracing.py) at the
 * end of every chat turn, forwarding the user's own accessToken cookie — same
 * trust pattern as every other Python-to-Spring call (e.g. knowledge-base search):
 * schoolId/userId/role are NEVER trusted from this payload, only from the
 * verified JWT (see AiTraceController/AiTraceService).
 */
public class AiTraceRequest {

    @NotBlank
    private String traceId;

    private String conversationId;
    private String userMessage;
    private String finalReply;

    /** Each map: {name, latencyMs, success, error, resultSummary} — metadata only, never raw tool payloads. */
    private List<Map<String, Object>> toolsCalled;

    /** Each map: {documentId, documentTitle, pageNumber, similarity, chunkPreview}. */
    private List<Map<String, Object>> ragResults;

    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Long totalLatencyMs;
    private boolean errored;
    private String errorMessage;
    private boolean interrupted;

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public String getUserMessage() { return userMessage; }
    public void setUserMessage(String userMessage) { this.userMessage = userMessage; }

    public String getFinalReply() { return finalReply; }
    public void setFinalReply(String finalReply) { this.finalReply = finalReply; }

    public List<Map<String, Object>> getToolsCalled() { return toolsCalled; }
    public void setToolsCalled(List<Map<String, Object>> toolsCalled) { this.toolsCalled = toolsCalled; }

    public List<Map<String, Object>> getRagResults() { return ragResults; }
    public void setRagResults(List<Map<String, Object>> ragResults) { this.ragResults = ragResults; }

    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }

    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }

    public Integer getTotalTokens() { return totalTokens; }
    public void setTotalTokens(Integer totalTokens) { this.totalTokens = totalTokens; }

    public Long getTotalLatencyMs() { return totalLatencyMs; }
    public void setTotalLatencyMs(Long totalLatencyMs) { this.totalLatencyMs = totalLatencyMs; }

    public boolean isErrored() { return errored; }
    public void setErrored(boolean errored) { this.errored = errored; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public boolean isInterrupted() { return interrupted; }
    public void setInterrupted(boolean interrupted) { this.interrupted = interrupted; }
}
