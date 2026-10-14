package com.betterself.growth.ai;

import com.betterself.growth.goal.GoalPolicy;
import com.betterself.growth.shared.api.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoalDraftContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    static final String VALID = """
        {"title":"四周整理学习记录","description":"每周整理三次学习记录，第四周完成总结。",
        "dimensionCode":"KNOWLEDGE","durationDays":28,"weeklyFocus":"先整理重点，再进行复盘。",
        "starterTasks":[{"title":"整理今日重点","estimatedMinutes":10,"difficulty":1}]}
        """;

    @Test
    void usesExactlyTheExistingSixFieldDraftContractAndGoalPolicy() throws Exception {
        JsonNode draft = GoalDraftContract.parse(mapper, VALID);
        assertThat(draft.properties()).extracting(java.util.Map.Entry::getKey)
            .containsExactly("title", "description", "dimensionCode", "durationDays", "weeklyFocus", "starterTasks");
        assertThat(draft.path("starterTasks").path(0).properties()).extracting(java.util.Map.Entry::getKey)
            .containsExactly("title", "estimatedMinutes", "difficulty");
        JsonNode duration = mapper.readTree(GoalDraftContract.SCHEMA).path("properties").path("durationDays");
        LocalDate start = LocalDate.of(2026, 9, 16);
        for (int days = 1; days <= 100; days++) {
            assertThat(GoalPolicy.hasValidDuration(start, start.plusDays(days - 1)))
                .isEqualTo(days >= duration.path("minimum").asInt() && days <= duration.path("maximum").asInt());
        }
    }

    @Test
    void rejectsMissingFieldsInsteadOfInventingNumericDefaults() throws Exception {
        for (String field : List.of("title", "description", "dimensionCode", "durationDays", "weeklyFocus", "starterTasks")) {
            ObjectNode draft = (ObjectNode) mapper.readTree(VALID);
            draft.remove(field);
            rejects(draft.toString());
        }
        for (String field : List.of("title", "estimatedMinutes", "difficulty")) {
            ObjectNode draft = (ObjectNode) mapper.readTree(VALID);
            ((ObjectNode) draft.path("starterTasks").path(0)).remove(field);
            rejects(draft.toString());
        }
    }

    @Test
    void rejectsWrongTypesExtraPropertiesAndInvalidTaskCounts() throws Exception {
        for (String value : List.of("\"28\"", "28.5", "null", "true", "{}", "99999999999999999999999")) {
            rejects(VALID.replace("\"durationDays\":28", "\"durationDays\":" + value));
        }
        rejects(VALID.replace("\"difficulty\":1", "\"difficulty\":\"1\""));
        rejects(VALID.replace("\"title\":\"四周整理学习记录\"", "\"title\":123"));
        rejects(VALID.replace("\"KNOWLEDGE\"", "\"CUSTOM\""));
        rejects(VALID.replace("\"difficulty\":1", "\"difficulty\":1,\"saved\":true"));
        ObjectNode draft = (ObjectNode) mapper.readTree(VALID);
        draft.put("sourceSessionPublicId", "forged-session");
        rejects(draft.toString());
        draft.remove("sourceSessionPublicId");
        draft.putArray("starterTasks");
        rejects(draft.toString());
        JsonNode task = mapper.readTree(VALID).path("starterTasks").path(0);
        draft.putArray("starterTasks").add(task).add(task).add(task).add(task);
        rejects(draft.toString());
    }

    @Test
    void rejectsTrailingDocumentsDuplicateKeysAndNonObjects() {
        rejects(VALID + " {}");
        rejects(VALID.replace("\"durationDays\":28", "\"durationDays\":28,\"durationDays\":42"));
        for (String value : List.of("null", "[]", "1", "true", "", "```json\n" + VALID + "\n```")) rejects(value);
    }

    @Test
    void keepsExistingAliasAndIntegerClampCompatibility() {
        JsonNode draft = GoalDraftContract.parse(mapper, VALID.replace("KNOWLEDGE", "知识")
            .replace("\"durationDays\":28", "\"durationDays\":7")
            .replace("\"estimatedMinutes\":10", "\"estimatedMinutes\":2")
            .replace("\"difficulty\":1", "\"difficulty\":5"));
        assertThat(draft.path("dimensionCode").asText()).isEqualTo("KNOWLEDGE");
        assertThat(draft.path("durationDays").asInt()).isEqualTo(14);
        assertThat(draft.path("starterTasks").path(0).path("estimatedMinutes").asInt()).isEqualTo(5);
        assertThat(draft.path("starterTasks").path(0).path("difficulty").asInt()).isEqualTo(3);
    }

    private void rejects(String value) {
        assertThatThrownBy(() -> GoalDraftContract.parse(mapper, value)).isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException) error).code()).isEqualTo("AI_INVALID_GOAL_TEMPLATE");
    }
}
