package com.betterself.growth.ai;

import com.betterself.growth.execution.IdempotencyService;
import com.betterself.growth.goal.PlanningService;
import com.betterself.growth.shared.api.ApiException;
import com.betterself.growth.shared.id.PublicIdGenerator;
import com.betterself.growth.safety.RiskLevel;
import com.betterself.growth.safety.SafetyService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SuggestionService {

    private static final String SCHEMA = """
        {
          "type":"object",
          "additionalProperties":false,
          "required":["items"],
          "properties":{
            "items":{
              "type":"array",
              "minItems":1,
              "maxItems":5,
              "items":{
                "type":"object",
                "additionalProperties":false,
                "required":["title","description","estimatedMinutes","difficulty","dimensionWeights","proposedLocalTime"],
                "properties":{
                  "title":{"type":"string","minLength":1,"maxLength":160},
                  "description":{"type":"string","maxLength":1000},
                  "estimatedMinutes":{"type":"integer","minimum":5,"maximum":60},
                  "difficulty":{"type":"integer","minimum":1,"maximum":3},
                  "dimensionWeights":{
                    "type":"object",
                    "minProperties":1,
                    "maxProperties":2,
                    "additionalProperties":{"type":"integer","minimum":1,"maximum":30}
                  },
                  "proposedLocalTime":{"type":"string","pattern":"^(?:[01][0-9]|2[0-3]):[0-5][0-9]$"}
                }
              }
            }
          }
        }
        """;

    private final JdbcTemplate jdbc;
    private final QwenProvider provider;
    private final SafetyService safety;
    private final PublicIdGenerator ids;
    private final IdempotencyService idempotency;
    private final PlanningService planning;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final AiPromptService prompts;

    public SuggestionService(
        JdbcTemplate jdbc,
        QwenProvider provider,
        SafetyService safety,
        PublicIdGenerator ids,
        IdempotencyService idempotency,
        PlanningService planning,
        ObjectMapper objectMapper,
        Clock clock,
        AiPromptService prompts
    ) {
        this.jdbc = jdbc;
        this.provider = provider;
        this.safety = safety;
        this.ids = ids;
        this.idempotency = idempotency;
        this.planning = planning;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.prompts = prompts;
    }

    @Transactional
    public SuggestionSetView generate(long userId, GenerateCommand command) {
        if (command == null || command.prompt() == null || command.prompt().isBlank() || command.prompt().length() > 4000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AI_PROMPT", "A prompt is required");
        }
        if (command.scene() == null || !List.of("STUDY", "FITNESS", "CAREER", "EMOTIONAL_SUPPORT").contains(command.scene())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AI_SCENE", "AI scene is invalid");
        }
        SafetyService.SafetyDecision decision = safety.classifyInput(command.scene(), command.prompt());
        if (!decision.allowGeneration()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AI_SAFETY_BLOCKED", "This request cannot generate task suggestions");
        }
        GoalRow goal = goal(userId, command.goalPublicId());
        QwenProvider.StructuredResult result = provider.generateStructured(
            prompts.suggestions(userId, command.scene(), command.prompt(), Map.of(
                "title", goal.title(), "description", goal.description() == null ? "" : goal.description(),
                "dimensionCode", goal.dimensionCode()
            ), SCHEMA)
        );
        List<SuggestionItem> items = parseAndValidate(userId, result.json());
        String output = items.stream().map(item -> item.title() + " " + item.description())
            .collect(java.util.stream.Collectors.joining("\n"));
        if (!safety.classifyOutput(command.scene(), output).allowGeneration()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AI_SAFETY_BLOCKED", "任务建议未通过安全检查");
        }
        String publicId = ids.next();
        jdbc.update(
            """
                insert into suggestion_set (
                    public_id, user_id, goal_id, status, model_name, provider_request_id, expires_at
                ) values (?, ?, ?, 'CONFIRMED', ?, ?, ?)
                """,
            publicId, userId, goal.id(), result.model(), result.requestId(),
            Timestamp.from(clock.instant().plus(Duration.ofMinutes(30)))
        );
        long setId = jdbc.queryForObject("select id from suggestion_set where public_id = ?", Long.class, publicId);
        int position = 0;
        for (SuggestionItem item : items) {
            position++;
            jdbc.update(
                """
                    insert into suggestion_item (
                        public_id, suggestion_set_id, position, title, description, estimated_minutes,
                        difficulty, dimension_weights, proposed_local_time
                    ) values (?, ?, ?, ?, ?, ?, ?, cast(? as json), ?)
                    """,
                ids.next(), setId, position, item.title(), item.description(), item.estimatedMinutes(),
                item.difficulty(), json(item.dimensionWeights()), Time.valueOf(item.proposedLocalTime())
            );
        }
        return set(userId, publicId);
    }

    public SuggestionSetView set(long userId, String publicId) {
        SetRow row = jdbc.query(
            """
                select s.id, s.public_id, g.public_id goal_public_id, s.status, s.model_name,
                       s.provider_request_id, s.expires_at
                from suggestion_set s join growth_goal g on g.id = s.goal_id
                where s.user_id = ? and s.public_id = ?
                """,
            rs -> rs.next() ? new SetRow(
                rs.getLong("id"), rs.getString("public_id"), rs.getString("goal_public_id"),
                rs.getString("status"), rs.getString("model_name"), rs.getString("provider_request_id"),
                rs.getTimestamp("expires_at").toInstant()
            ) : null,
            userId, publicId
        );
        if (row == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SUGGESTION_SET_NOT_FOUND", "Resource not found");
        }
        List<SuggestionItemView> items = jdbc.query(
            """
                select public_id, title, description, estimated_minutes, difficulty,
                       dimension_weights, proposed_local_time, adopted_task_id
                from suggestion_item where suggestion_set_id = ? order by position
                """,
            (rs, index) -> new SuggestionItemView(
                rs.getString("public_id"), rs.getString("title"), rs.getString("description"),
                rs.getInt("estimated_minutes"), rs.getInt("difficulty"), readWeights(rs.getString("dimension_weights")),
                rs.getTime("proposed_local_time").toLocalTime(), rs.getObject("adopted_task_id") != null
            ),
            row.id()
        );
        return new SuggestionSetView(
            row.publicId(), row.goalPublicId(), row.status(), row.model(), row.providerRequestId(), row.expiresAt(), items
        );
    }

    @Transactional
    public AdoptionResult adopt(long userId, String publicId, String key, AdoptCommand command) {
        Long locked = jdbc.query(
            "select id from suggestion_set where user_id = ? and public_id = ? for update",
            rs -> rs.next() ? rs.getLong(1) : null,
            userId, publicId
        );
        if (locked == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SUGGESTION_SET_NOT_FOUND", "Resource not found");
        }
        SuggestionSetView view = set(userId, publicId);
        String operation = "ADOPT_SUGGESTIONS:" + publicId;
        IdempotencyService.BeginResult begin = idempotency.begin(userId, operation, key, command);
        if (begin.replay()) {
            return idempotency.replay(begin, AdoptionResult.class);
        }
        if (view.expiresAt().isBefore(clock.instant())) {
            jdbc.update("update suggestion_set set status = 'EXPIRED' where user_id = ? and public_id = ?", userId, publicId);
            throw new ApiException(HttpStatus.GONE, "SUGGESTION_SET_EXPIRED", "Suggestion set has expired");
        }
        if ("ADOPTED".equals(view.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "SUGGESTION_SET_ALREADY_ADOPTED", "Suggestion set was already adopted");
        }
        if (command == null || command.weeklyPlanPublicId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WEEKLY_PLAN_REQUIRED", "Weekly plan is required");
        }
        List<String> selected = command == null || command.itemPublicIds() == null
            ? view.items().stream().map(SuggestionItemView::publicId).toList()
            : command.itemPublicIds();
        List<String> taskIds = new ArrayList<>();
        for (SuggestionItemView item : view.items()) {
            if (!selected.contains(item.publicId())) {
                continue;
            }
            PlanningService.TaskView task = planning.createTask(userId, new PlanningService.CreateTaskCommand(
                command.weeklyPlanPublicId(), null, item.title(), item.description(), item.estimatedMinutes(), item.difficulty(),
                null, item.dimensionWeights(), item.proposedLocalTime(), command.activeFrom(), command.activeUntil(),
                null, null
            ));
            long taskId = jdbc.queryForObject("select id from user_task where user_id = ? and public_id = ?", Long.class, userId, task.publicId());
            jdbc.update("update suggestion_item set adopted_task_id = ? where public_id = ?", taskId, item.publicId());
            taskIds.add(task.publicId());
        }
        jdbc.update(
            "update suggestion_set set status = 'ADOPTED', adopted_at = ? where user_id = ? and public_id = ?",
            Timestamp.from(clock.instant()), userId, publicId
        );
        AdoptionResult result = new AdoptionResult(publicId, taskIds);
        idempotency.complete(userId, operation, key, result, publicId);
        return result;
    }

    private GoalRow goal(long userId, String publicId) {
        GoalRow row = jdbc.query(
            """
                select g.id, left(g.title, 160) title, left(g.description, 1000) description, d.code
                from growth_goal g join growth_dimension d on d.id = g.dimension_id
                where g.user_id = ? and g.public_id = ? and g.status = 'ACTIVE'
                """,
            rs -> rs.next() ? new GoalRow(rs.getLong("id"), rs.getString("title"), rs.getString("description"), rs.getString("code")) : null,
            userId, publicId
        );
        if (row == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GOAL_NOT_FOUND", "Resource not found");
        }
        return row;
    }

    private List<SuggestionItem> parseAndValidate(long userId, String value) {
        try {
            JsonNode itemsNode = objectMapper.readTree(value).path("items");
            if (!itemsNode.isArray() || itemsNode.isEmpty() || itemsNode.size() > 5) {
                throw invalidOutput();
            }
            List<String> allowedDimensions = jdbc.queryForList(
                "select d.code from growth_dimension d join user_dimension ud on ud.dimension_id = d.id where ud.user_id = ? and ud.active = 1",
                String.class, userId
            );
            QuietHours quietHours = jdbc.query(
                "select quiet_hours_start, quiet_hours_end from user_preference where user_id = ?",
                rs -> rs.next() ? new QuietHours(
                    rs.getTime(1) == null ? null : rs.getTime(1).toLocalTime(),
                    rs.getTime(2) == null ? null : rs.getTime(2).toLocalTime()
                ) : new QuietHours(null, null),
                userId
            );
            List<SuggestionItem> items = new ArrayList<>();
            for (JsonNode node : itemsNode) {
                Map<String, Integer> weights = objectMapper.convertValue(
                    node.path("dimensionWeights"),
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Integer.class)
                );
                int weightTotal = weights.values().stream().mapToInt(Integer::intValue).sum();
                int minutes = node.path("estimatedMinutes").asInt();
                int difficulty = node.path("difficulty").asInt();
                LocalTime localTime = LocalTime.parse(node.path("proposedLocalTime").asText("09:00"));
                if (node.path("title").asText().isBlank() || minutes < 5 || minutes > 60 || difficulty < 1 || difficulty > 3
                    || weights.isEmpty() || !allowedDimensions.containsAll(weights.keySet()) || weightTotal < 1 || weightTotal > 30
                    || quietHours.contains(localTime)) {
                    throw invalidOutput();
                }
                items.add(new SuggestionItem(
                    node.path("title").asText(), node.path("description").asText(null), minutes,
                    difficulty, weights, localTime
                ));
            }
            return items;
        } catch (ApiException exception) {
            throw exception;
        } catch (RuntimeException | JsonProcessingException exception) {
            throw invalidOutput();
        }
    }

    private ApiException invalidOutput() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_INVALID_SUGGESTIONS", "AI suggestions were invalid; use manual task creation");
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize suggestion", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> readWeights(String value) {
        try {
            return objectMapper.readValue(value, LinkedHashMap.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored suggestion is invalid", exception);
        }
    }

    public record GenerateCommand(String scene, String goalPublicId, String prompt) {
    }

    public record AdoptCommand(
        String weeklyPlanPublicId,
        List<String> itemPublicIds,
        LocalDate activeFrom,
        LocalDate activeUntil
    ) {
    }

    public record SuggestionSetView(
        String publicId,
        String goalPublicId,
        String status,
        String model,
        String providerRequestId,
        java.time.Instant expiresAt,
        List<SuggestionItemView> items
    ) {
    }

    public record SuggestionItemView(
        String publicId,
        String title,
        String description,
        int estimatedMinutes,
        int difficulty,
        Map<String, Integer> dimensionWeights,
        LocalTime proposedLocalTime,
        boolean adopted
    ) {
    }

    public record AdoptionResult(String suggestionSetPublicId, List<String> taskPublicIds) {
    }

    private record SuggestionItem(
        String title,
        String description,
        int estimatedMinutes,
        int difficulty,
        Map<String, Integer> dimensionWeights,
        LocalTime proposedLocalTime
    ) {
    }

    private record GoalRow(long id, String title, String description, String dimensionCode) {
    }

    private record QuietHours(LocalTime start, LocalTime end) {
        boolean contains(LocalTime value) {
            if (start == null || end == null || start.equals(end)) {
                return false;
            }
            return start.isBefore(end)
                ? !value.isBefore(start) && value.isBefore(end)
                : !value.isBefore(start) || value.isBefore(end);
        }
    }

    private record SetRow(
        long id,
        String publicId,
        String goalPublicId,
        String status,
        String model,
        String providerRequestId,
        java.time.Instant expiresAt
    ) {
    }
}
