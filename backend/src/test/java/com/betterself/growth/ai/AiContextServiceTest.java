package com.betterself.growth.ai;

import com.betterself.growth.daily.DailyStatusService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiContextServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DailyStatusService daily = mock(DailyStatusService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-16T23:30:00Z"), ZoneOffset.UTC);
    private final AiContextService context = new AiContextService(jdbc, daily, clock);

    @Test
    void scopesPrivateQueriesToOwnerAndUsesOnlyTodaysStatusInTheirTimezone() {
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(7L))).thenReturn("Asia/Shanghai");
        Map<String, Object> snapshot = context.snapshot(7L, "STUDY");

        assertThat(snapshot.get("localDate")).isEqualTo("2026-09-17");
        assertThat(snapshot).containsEntry("todayStatus", null);
        verify(daily).status(7L, LocalDate.of(2026, 9, 17));
        verify(daily, never()).latest(any(Long.class));
        verify(jdbc).queryForList(argThat(sql -> sql.contains("from user_preference where user_id = ?")), eq(7L));
        verify(jdbc).queryForList(argThat(sql -> sql.contains("g.user_id = ?") && sql.contains("g.status = 'ACTIVE'")), eq(7L));
        verify(jdbc).queryForList(argThat(sql -> sql.contains("ud.user_id = ?") && sql.contains("d.owner_user_id = ?")), eq(7L), eq(7L));
        verify(jdbc).queryForList(argThat(sql -> sql.contains("review_status = 'PUBLISHED'")
            && sql.contains("between 5 and 60") && sql.contains("scene = ?") && sql.contains("limit 100")), eq("STUDY"));
    }

    @Test
    void includesActualDailyBudgetWithoutSendingUnneededPrivateFields() {
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(7L))).thenReturn("Asia/Shanghai");
        when(daily.status(7L, LocalDate.of(2026, 9, 17))).thenReturn(new DailyStatusService.StatusView(
            "private-status-id", LocalDate.of(2026, 9, 17), "LOW", 10, "SHRINK", clock.instant()
        ));
        assertThat(context.snapshot(7L, "STUDY").get("todayStatus").toString())
            .contains("availableMinutes=10", "advice=SHRINK").doesNotContain("private-status-id", "updatedAt");
    }

    @Test
    void retrievesRelevantPublishedActionsInsteadOfAlwaysTakingTheFirstTemplates() {
        List<Map<String, Object>> templates = new java.util.ArrayList<>();
        for (int index = 0; index < 10; index++) {
            templates.add(Map.of("title", "预习一节课程" + index, "description", "浏览标题和目录"));
        }
        Map<String, Object> review = Map.of("title", "复盘一道错题", "description", "记录错误原因、正确路径和下次识别线索");
        templates.add(review);
        List<Map<String, Object>> relevant = AiContextService.relevantTemplates(templates, "我想复盘数学错题");
        assertThat(relevant).hasSize(8);
        assertThat(relevant.getFirst()).isEqualTo(review);
        assertThat(AiContextService.relevantTemplates(List.of(), "复盘")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void preservesRecentTurnsInOrderWithABoundedBudgetAndNoDeletedOrExpiredMessages() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(7L), eq(4L), any(Timestamp.class))).thenReturn(List.of(
            new QwenProvider.ChatMessage("USER", "最新更正"),
            new QwenProvider.ChatMessage("ASSISTANT", "建议内容".repeat(4000)),
            new QwenProvider.ChatMessage("USER", "过旧的内容")
        ));

        List<QwenProvider.ChatMessage> history = context.history(7L, 4L, false);

        assertThat(history).hasSize(2);
        assertThat(history.getLast().content()).isEqualTo("最新更正");
        assertThat(history.stream().mapToInt(message -> message.content().length()).sum()).isEqualTo(12_000);
        verify(jdbc).query(argThat(sql -> sql.contains("user_id = ? and session_id = ?")
            && sql.contains("deleted_at is null and expires_at > ?") && sql.contains("status = 'COMPLETED'")
            && sql.contains("risk_level in ('L0', 'L1')") && sql.contains("limit 12")),
            any(RowMapper.class), eq(7L), eq(4L), eq(Timestamp.from(clock.instant())));
    }
}
