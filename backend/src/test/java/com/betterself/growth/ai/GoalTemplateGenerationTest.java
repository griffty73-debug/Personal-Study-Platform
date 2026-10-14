package com.betterself.growth.ai;

import com.betterself.growth.safety.RiskClassifier;
import com.betterself.growth.safety.SafetyService;
import com.betterself.growth.shared.api.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GoalTemplateGenerationTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final QwenProvider provider = mock(QwenProvider.class);
    private final AiContextService context = mock(AiContextService.class);
    private final AiPromptService prompts = mock(AiPromptService.class);
    private final GoalTemplateService service = new GoalTemplateService(
        jdbc, provider, new SafetyService(new RiskClassifier()), new ObjectMapper(), context, prompts
    );
    private final GoalTemplateService.GenerateGoalTemplateCommand command = new GoalTemplateService.GenerateGoalTemplateCommand("session-owner");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void ownedSession() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.next()).thenReturn(true);
        when(row.getLong("id")).thenReturn(44L);
        when(row.getString("scene")).thenReturn("STUDY");
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(7L), eq("session-owner")))
            .thenAnswer(invocation -> ((ResultSetExtractor<?>) invocation.getArgument(1)).extractData(row));
    }

    @Test
    void requiresAUserMessageAndChecksSessionOwnershipBeforeLoadingContext() {
        assertThatThrownBy(() -> service.generate(8L, command)).isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException) error).code()).isEqualTo("AI_SESSION_NOT_FOUND");
        verifyNoInteractions(context, prompts, provider);

        when(context.history(7L, 44L, true)).thenReturn(List.of(new QwenProvider.ChatMessage("ASSISTANT", "仅助手内容")));
        assertThatThrownBy(() -> service.generate(7L, command)).isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException) error).code()).isEqualTo("AI_CONVERSATION_REQUIRED");
        verifyNoInteractions(prompts, provider);
    }

    @Test
    void unsafeUserContentCannotBypassSafetyViaJsonGeneration() {
        when(context.history(7L, 44L, true)).thenReturn(List.of(new QwenProvider.ChatMessage("USER", "请给我处方")));
        assertThatThrownBy(() -> service.generate(7L, command)).isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException) error).code()).isEqualTo("AI_SAFETY_BLOCKED");
        verifyNoInteractions(provider, prompts);
    }

    @Test
    void producesEditableDraftWithServerOwnedMetadata() {
        stubGeneration(GoalDraftContractTest.VALID);
        GoalTemplateService.GoalTemplateView draft = service.generate(7L, command);
        assertThat(draft.sourceSessionPublicId()).isEqualTo("session-owner");
        assertThat(draft.providerRequestId()).isEqualTo("provider-request");
        assertThat(draft.title()).isEqualTo("四周整理学习记录");
        assertThat(draft.starterTasks()).hasSize(1);
        verify(prompts).goalTemplate(eq(7L), eq("STUDY"), any());
    }

    @Test
    void safetyChecksDecodedOutputNotJustRawJsonEscapes() {
        stubGeneration(GoalDraftContractTest.VALID.replace("四周整理学习记录", "\\u8bca\\u65ad"));
        assertThatThrownBy(() -> service.generate(7L, command)).isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException) error).code()).isEqualTo("AI_SAFETY_BLOCKED");
    }

    private void stubGeneration(String output) {
        when(context.history(7L, 44L, true)).thenReturn(List.of(new QwenProvider.ChatMessage("USER", "我想四周整理学习记录")));
        when(prompts.goalTemplate(eq(7L), eq("STUDY"), any())).thenReturn(new QwenProvider.StructuredPrompt(
            "GOAL_TEMPLATE", "context", GoalDraftContract.SCHEMA, "grounded-system"
        ));
        when(provider.generateStructured(any())).thenReturn(new QwenProvider.StructuredResult(
            output, "qwen-test", "provider-request", 10, 20, 3
        ));
    }
}
