package com.betterself.growth.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

@Component
public class AiKnowledgeBase {

    private final Catalog catalog;

    public AiKnowledgeBase(ObjectMapper mapper) {
        try {
            catalog = mapper.readValue(resource("ai/knowledge-base.json"), Catalog.class);
            if (catalog.version() == null || catalog.entries().isEmpty()
                || catalog.entries().stream().anyMatch(entry -> entry.sources().isEmpty() || entry.content().isBlank())) {
                throw new IllegalStateException("AI knowledge must contain traceable local sources");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load system AI knowledge", exception);
        }
    }

    public String retrieve(String scene, String query) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        StringBuilder content = new StringBuilder("系统内知识库版本：").append(catalog.version());
        for (Entry entry : catalog.entries()) {
            if (entry.scenes().isEmpty() || entry.scenes().contains(scene)
                || entry.keywords().stream().anyMatch(normalized::contains)) {
                content.append("\n\n[").append(entry.id()).append("] ").append(entry.content())
                    .append("\n本地依据：").append(String.join("、", entry.sources()));
            }
        }
        return content.toString();
    }

    static String resource(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load AI resource: " + path, exception);
        }
    }

    record Catalog(String version, List<Entry> entries) {
    }

    record Entry(String id, List<String> scenes, List<String> keywords, String content, List<String> sources) {
    }
}
