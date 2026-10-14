package com.betterself.growth.ai;

import com.betterself.growth.daily.DailyStatusService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AiContextService {

    private final JdbcTemplate jdbc;
    private final DailyStatusService dailyStatus;
    private final Clock clock;

    public AiContextService(JdbcTemplate jdbc, DailyStatusService dailyStatus, Clock clock) {
        this.jdbc = jdbc;
        this.dailyStatus = dailyStatus;
        this.clock = clock;
    }

    public Map<String, Object> snapshot(long userId, String scene) {
        return snapshot(userId, scene, "");
    }

    public Map<String, Object> snapshot(long userId, String scene, String query) {
        String timezone = jdbc.queryForObject("select timezone from sys_user where id = ?", String.class, userId);
        LocalDate today = clock.instant().atZone(ZoneId.of(timezone == null ? "UTC" : timezone)).toLocalDate();
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("localDate", today.toString());
        context.put("scene", scene);
        context.put("planningPreferences", jdbc.queryForList(
            """
                select daily_minutes dailyMinutes, weekly_frequency weeklyFrequency,
                       preferred_difficulty preferredDifficulty, quiet_hours_start quietHoursStart,
                       quiet_hours_end quietHoursEnd
                from user_preference where user_id = ?
                """, userId
        ));
        context.put("dimensions", jdbc.queryForList(
            """
                select d.code, d.name from growth_dimension d
                join user_dimension ud on ud.dimension_id = d.id
                where ud.user_id = ? and ud.active = 1 and d.archived_at is null
                  and (d.is_system = 1 or d.owner_user_id = ?)
                order by d.id
                """, userId, userId
        ));
        context.put("activeGoals", jdbc.queryForList(
            """
                select left(g.title, 160) title, left(g.description, 1000) description,
                       d.code dimensionCode, g.start_date startDate, g.end_date endDate
                from growth_goal g join growth_dimension d on d.id = g.dimension_id
                where g.user_id = ? and g.status = 'ACTIVE' order by g.id desc limit 3
                """, userId
        ));
        DailyStatusService.StatusView status = dailyStatus.status(userId, today);
        context.put("todayStatus", status == null ? null : Map.of(
            "localDate", today.toString(), "energy", status.energy(),
            "availableMinutes", status.availableMinutes(), "advice", status.advice()
        ));
        List<Map<String, Object>> templates = jdbc.queryForList(
            """
                select left(name, 160) title, left(description, 1000) description, dimension_code dimensionCode,
                       estimated_minutes estimatedMinutes, difficulty
                from task_template where scene = ? and review_status = 'PUBLISHED'
                  and estimated_minutes between 5 and 60 and difficulty between 1 and 3
                order by version desc, public_id limit 100
                """, scene
        );
        context.put("publishedTaskTemplates", relevantTemplates(templates, query));
        return context;
    }

    static List<Map<String, Object>> relevantTemplates(List<Map<String, Object>> templates, String query) {
        Set<String> terms = terms(query);
        return templates.stream().sorted(Comparator.comparingInt((Map<String, Object> template) -> {
            Set<String> titleTerms = terms(String.valueOf(template.get("title")));
            Set<String> descriptionTerms = terms(String.valueOf(template.get("description")));
            titleTerms.retainAll(terms);
            descriptionTerms.retainAll(terms);
            return titleTerms.size() * 3 + descriptionTerms.size();
        }).reversed()).limit(8).toList();
    }

    private static Set<String> terms(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ");
        Set<String> terms = new HashSet<>();
        for (String word : normalized.split(" +")) {
            if (word.length() >= 2) terms.add(word);
            for (int index = 0; index < word.length() - 1; index++) terms.add(word.substring(index, index + 2));
        }
        return terms;
    }

    public List<QwenProvider.ChatMessage> history(long userId, long sessionId, boolean includeUnsafe) {
        List<QwenProvider.ChatMessage> recent = jdbc.query(
            """
                select role, content from ai_message
                where user_id = ? and session_id = ? and deleted_at is null and expires_at > ?
                  and status = 'COMPLETED' and role in ('USER', 'ASSISTANT')
                """ + (includeUnsafe ? "" : " and risk_level in ('L0', 'L1') ")
                + " order by id desc limit 12",
            (result, row) -> new QwenProvider.ChatMessage(result.getString("role"), result.getString("content")),
            userId, sessionId, java.sql.Timestamp.from(clock.instant())
        );
        List<QwenProvider.ChatMessage> bounded = new ArrayList<>();
        int remaining = 12_000;
        for (QwenProvider.ChatMessage message : recent) {
            if (remaining == 0) break;
            String content = message.content();
            if (content == null || content.isBlank()) continue;
            if (content.length() > remaining) content = content.substring(0, remaining);
            bounded.add(new QwenProvider.ChatMessage(message.role(), content));
            remaining -= content.length();
        }
        Collections.reverse(bounded);
        return List.copyOf(bounded);
    }
}
