package com.betterself.growth.ai;

import com.betterself.growth.shared.api.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

@Service
public class AiPromptService {

    private final AiKnowledgeBase knowledge;
    private final AiContextService context;
    private final ObjectMapper mapper;
    private final String system = AiKnowledgeBase.resource("ai/prompts/system.md");
    private final String chat = AiKnowledgeBase.resource("ai/prompts/chat.md");
    private final String goalTemplate = AiKnowledgeBase.resource("ai/prompts/goal-template.md");

    public AiPromptService(AiKnowledgeBase knowledge, AiContextService context, ObjectMapper mapper) {
        this.knowledge = knowledge;
        this.context = context;
        this.mapper = mapper;
    }

    public QwenProvider.ChatPrompt chat(
        long userId, String scene, String publishedPrompt, String message, List<QwenProvider.ChatMessage> history
    ) {
        return new QwenProvider.ChatPrompt(
            scene, publishedPrompt + "\n\n" + groundedSystem(scene, message) + "\n\n" + chat
                + "\n\n目标草案 JSON Schema：\n" + GoalDraftContract.SCHEMA,
            json(Map.of("context", context.snapshot(userId, scene, message), "message", message)), history
        );
    }

    public QwenProvider.StructuredPrompt goalTemplate(
        long userId, String scene, List<QwenProvider.ChatMessage> conversation
    ) {
        String query = conversation.stream().filter(message -> "USER".equals(message.role()))
            .map(QwenProvider.ChatMessage::content).collect(java.util.stream.Collectors.joining("\n"));
        Map<String, Object> snapshot = context.snapshot(userId, scene, query);
        requireTemplates(snapshot);
        return new QwenProvider.StructuredPrompt(
            "GOAL_TEMPLATE",
            json(Map.of("context", snapshot, "conversation", conversation)),
            GoalDraftContract.SCHEMA,
            groundedSystem(scene, json(conversation)) + "\n\n" + goalTemplate
        );
    }

    public QwenProvider.StructuredPrompt suggestions(
        long userId, String scene, String request, Map<String, Object> goal, String schema
    ) {
        Map<String, Object> snapshot = context.snapshot(userId, scene, request + "\n" + goal.get("title"));
        requireTemplates(snapshot);
        return new QwenProvider.StructuredPrompt(
            scene, json(Map.of("context", snapshot, "goal", goal, "message", request)), schema,
            groundedSystem(scene, request) + """

                任务建议模式：围绕本次提供的真实 goal 生成 1—2 个可编辑任务，只返回 Schema 中的 items JSON。
                不要把建议 items 格式和六字段目标草案格式混用。使用中文，时间为 HH:mm。
                dimensionWeights 只用 context.dimensions 中已启用的维度，优先目标维度；每项最多两个维度，权重总和 1—30。
                避开 planningPreferences 中的免打扰时间；时长 5—60 分钟、难度 1—3。
                根据已发布模板有限改写，不编造用户偏好或已完成事项。
                """
        );
    }

    private String groundedSystem(String scene, String query) {
        return system + "\n\n" + knowledge.retrieve(scene, query);
    }

    private void requireTemplates(Map<String, Object> snapshot) {
        if (!(snapshot.get("publishedTaskTemplates") instanceof List<?> templates) || templates.isEmpty()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_KNOWLEDGE_UNAVAILABLE", "当前场景缺少已发布任务模板，请使用手动计划");
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize AI context", exception);
        }
    }
}
