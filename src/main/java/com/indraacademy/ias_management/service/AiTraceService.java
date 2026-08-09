package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.AiTraceRequest;
import com.indraacademy.ias_management.entity.AiTraceEvent;
import com.indraacademy.ias_management.repository.AiTraceEventRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AiTraceService {

    private static final Logger log = LoggerFactory.getLogger(AiTraceService.class);

    @Autowired private AiTraceEventRepository repository;
    @Autowired private AuthService authService;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private ObjectMapper objectMapper;

    public void record(AiTraceRequest request) {
        AiTraceEvent event = new AiTraceEvent();
        // schoolId/userId/role come from the verified JWT, never the request body —
        // same trust boundary as every other endpoint Python calls on the user's behalf.
        event.setSchoolId(securityUtil.getSchoolId());
        event.setUserId(authService.getUserId());
        event.setRole(authService.getRole());

        event.setTraceId(request.getTraceId());
        event.setConversationId(request.getConversationId());
        event.setUserMessage(request.getUserMessage());
        event.setFinalReply(request.getFinalReply());
        event.setToolsCalledJson(toJson(request.getToolsCalled()));
        event.setRagResultsJson(toJson(request.getRagResults()));
        event.setPromptTokens(request.getPromptTokens());
        event.setCompletionTokens(request.getCompletionTokens());
        event.setTotalTokens(request.getTotalTokens());
        event.setTotalLatencyMs(request.getTotalLatencyMs());
        event.setErrored(request.isErrored());
        event.setErrorMessage(request.getErrorMessage());
        event.setInterrupted(request.isInterrupted());

        repository.save(event);
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Failed to serialize AI trace field: {}", e.getMessage());
            return null;
        }
    }
}
