package com.betterself.growth.ai;

import com.betterself.growth.safety.SafetyService;
import com.betterself.growth.shared.api.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class GoalTemplateService {

    private final JdbcTemplate jdbc;
    private final QwenProvider provider;
    private final SafetyService safety;
    private final ObjectMapper objectMapper;
    private final AiContextService context;
    private final AiPromptService prompts;

    public GoalTemplateService(
        JdbcTemplate jdbc, QwenProvider provider, SafetyService safety, ObjectMapper objectMapper,
        AiContextService context, AiPromptService prompts
    ) {
        this.jdbc = jdbc;
        this.provider = provider;
        this.safety = safety;
        this.objectMapper = objectMapper;
        this.context = context;
        this.prompts = prompts;
    }

    public GoalTemplateView generate(long userId, GenerateGoalTemplateCommand command) {
        if (command == null || command.sessionPublicId() == null || command.sessionPublicId().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AI_SESSION_REQUIRED", "请先进行一段对话");
        }
        SessionRow session = jdbc.query(
            "select id, scene from ai_session where user_id = ? and public_id = ? and status <> 'DELETED'",
            rs -> rs.next() ? new SessionRow(rs.getLong("id"), rs.getString("scene")) : null,
            userId,
            command.sessionPublicId()
        );
        if (session == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "AI_SESSION_NOT_FOUND", "对话不存在");
        }
        List<QwenProvider.ChatMessage> messages = context.history(userId, session.id(), true);
        if (messages.stream().noneMatch(message -> "USER".equals(message.role()))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AI_CONVERSATION_REQUIRED", "请先告诉 AI 你想实现什么");
        }
        String conversation = messages.stream()
            .map(item -> item.role() + ": " + item.content())
            .reduce((left, right) -> left + "\n" + right)
            .orElse("");
        SafetyService.SafetyDecision decision = safety.classifyInput(session.scene(), conversation);
        if (!decision.allowGeneration()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AI_SAFETY_BLOCKED", "当前对话不能生成目标模板");
        }
        QwenProvider.StructuredResult result = provider.generateStructured(prompts.goalTemplate(userId, session.scene(), messages));
        GoalTemplateView draft = parse(command.sessionPublicId(), result);
        String output = draft.title() + "\n" + draft.description() + "\n" + draft.weeklyFocus() + "\n"
            + draft.starterTasks().stream().map(StarterTaskView::title).collect(java.util.stream.Collectors.joining("\n"));
        if (!safety.classifyOutput(session.scene(), output).allowGeneration()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AI_SAFETY_BLOCKED", "目标草案未通过安全检查，请调整目标后重试");
        }
        return draft;
    }

    GoalTemplateView parse(String sessionPublicId, QwenProvider.StructuredResult result) {
        JsonNode root = GoalDraftContract.parse(objectMapper, result.json());
        List<StarterTaskView> tasks = new ArrayList<>();
        for (JsonNode task : root.path("starterTasks")) {
            tasks.add(new StarterTaskView(
                task.path("title").asText(), task.path("estimatedMinutes").intValue(), task.path("difficulty").intValue()
            ));
        }
        return new GoalTemplateView(
            sessionPublicId, root.path("title").asText(), root.path("description").asText(),
            root.path("dimensionCode").asText(), root.path("durationDays").intValue(), root.path("weeklyFocus").asText(),
            tasks, result.model(), result.requestId()
        );
    }

    /**
     * 模型偶发返回变体维度码（大小写、中文名、英文别名等），这里统一归一到系统五个维度。
     */
    static String normalizeDimension(String raw) {
        return GoalDraftContract.normalizeDimension(raw);
    }

    public record GenerateGoalTemplateCommand(String sessionPublicId) {
    }

    public record GoalTemplateView(
        String sourceSessionPublicId,
        String title,
        String description,
        String dimensionCode,
        int durationDays,
        String weeklyFocus,
        List<StarterTaskView> starterTasks,
        String model,
        String providerRequestId
    ) {
    }

    public record StarterTaskView(String title, int estimatedMinutes, int difficulty) {
    }

    private record SessionRow(long id, String scene) {
    }

}
