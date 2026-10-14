package com.kk.biz.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

public final class TaskAiModels {
    private TaskAiModels() {}

    @Data public static class ChatRequest { private String sessionId; private String message; private ClarifyAnswer clarifyAnswer; }
    @Data public static class ClarifyAnswer { private String field; private String optionId; private String label; }
    @Data public static class ConfirmRequest { private String sessionId; private List<DraftItem> items; }
    @Data public static class CancelRequest { private String sessionId; }
    @Data public static class DraftItem {
        private String tempId; private Long projectId; private String projectName; private String title; private String content;
        private String startAt; private String endAt; private Integer status; private String statusLabel;
    }
    public record ChatResponse(String sessionId, int protocolVersion, String replyText,
                               List<Map<String, Object>> blocks, boolean pendingConfirm,
                               List<Map<String, Object>> toolTrace) {}
    public record ConfirmResponse(String sessionId, int protocolVersion, List<Map<String, Object>> blocks,
                                  Map<String, Object> done) {}
}
