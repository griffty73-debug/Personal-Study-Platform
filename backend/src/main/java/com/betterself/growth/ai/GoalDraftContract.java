package com.betterself.growth.ai;

import com.betterself.growth.shared.api.ApiException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;

import java.util.Locale;

final class GoalDraftContract {

    static final String SCHEMA = AiKnowledgeBase.resource("ai/goal-draft.schema.json");
    private static final JsonNode CONTRACT = schema();

    private GoalDraftContract() {
    }

    static JsonNode parse(ObjectMapper mapper, String value) {
        try {
            JsonNode root = mapper.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .readTree(value);
            if (root == null || !root.isObject() || !root.path("dimensionCode").isTextual()) throw invalid();
            String dimension = normalizeDimension(root.path("dimensionCode").textValue());
            if (dimension == null) throw invalid();
            ((ObjectNode) root).put("dimensionCode", dimension);
            return normalize(root, CONTRACT);
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private static JsonNode normalize(JsonNode value, JsonNode schema) {
        return switch (schema.path("type").asText()) {
            case "object" -> {
                if (!value.isObject()) throw invalid();
                JsonNode properties = schema.path("properties");
                var names = value.fieldNames();
                while (names.hasNext()) {
                    if (!properties.has(names.next())) throw invalid();
                }
                for (JsonNode required : schema.path("required")) {
                    if (!value.hasNonNull(required.asText())) throw invalid();
                }
                ObjectNode object = JsonNodeFactory.instance.objectNode();
                properties.properties().forEach(field ->
                    object.set(field.getKey(), normalize(value.path(field.getKey()), field.getValue())));
                yield object;
            }
            case "array" -> {
                if (!value.isArray() || value.size() < schema.path("minItems").asInt()
                    || value.size() > schema.path("maxItems").asInt()) throw invalid();
                ArrayNode array = JsonNodeFactory.instance.arrayNode();
                value.forEach(item -> array.add(normalize(item, schema.path("items"))));
                yield array;
            }
            case "string" -> {
                if (!value.isTextual() || value.textValue().isBlank()) throw invalid();
                String text = value.textValue().trim();
                if (schema.has("enum")) {
                    boolean allowed = false;
                    for (JsonNode option : schema.path("enum")) {
                        if (option.asText().equals(text)) allowed = true;
                    }
                    if (!allowed) throw invalid();
                }
                int maxLength = schema.path("maxLength").asInt(Integer.MAX_VALUE);
                yield JsonNodeFactory.instance.textNode(text.length() > maxLength ? text.substring(0, maxLength).trim() : text);
            }
            case "integer" -> {
                if (!value.isIntegralNumber() || !value.canConvertToInt()) throw invalid();
                int number = Math.max(schema.path("minimum").asInt(),
                    Math.min(schema.path("maximum").asInt(), value.intValue()));
                yield JsonNodeFactory.instance.numberNode(number);
            }
            default -> throw invalid();
        };
    }

    static String normalizeDimension(String raw) {
        if (raw == null) return null;
        return switch (raw.trim().toUpperCase(Locale.ROOT).replace("-", "_").replace(" ", "_")) {
            case "KNOWLEDGE", "智力", "知识", "ZHILI", "XUEXI", "STUDY", "LEARNING", "ACADEMIC" -> "KNOWLEDGE";
            case "HEALTH", "体力", "健康", "TILI", "JIANKANG", "FITNESS", "BODY" -> "HEALTH";
            case "CAREER", "职场", "职业", "执行力", "ZHICHANG", "WORK", "PROFESSIONAL" -> "CAREER";
            case "RELATIONSHIP", "社交", "关系", "社交力", "SHEJIAO", "RELATION", "SOCIAL" -> "RELATIONSHIP";
            case "WELLBEING", "情绪", "心境", "心境力", "EMOTIONAL", "EMOTION", "MINDSET", "WELL_BEING" -> "WELLBEING";
            default -> null;
        };
    }

    private static JsonNode schema() {
        try {
            return new ObjectMapper().readTree(SCHEMA);
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid goal draft schema", exception);
        }
    }

    static ApiException invalid() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_INVALID_GOAL_TEMPLATE", "AI 返回的目标模板格式无效，请重试");
    }
}
