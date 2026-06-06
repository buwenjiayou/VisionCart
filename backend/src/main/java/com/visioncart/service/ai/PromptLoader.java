package com.visioncart.service.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PromptLoader {
    private static final Logger log = LoggerFactory.getLogger(PromptLoader.class);
    private static final String PROMPTS_DIR = "prompts/";

    private final Map<String, PromptData> cache = new ConcurrentHashMap<>();

    public String getPrompt(String name) {
        return load(name).prompt();
    }

    public String getVersion(String name) {
        return load(name).version();
    }

    private PromptData load(String name) {
        return cache.computeIfAbsent(name, this::loadFromResource);
    }

    @SuppressWarnings("unchecked")
    private PromptData loadFromResource(String name) {
        String path = PROMPTS_DIR + name + ".yml";
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalStateException("Prompt file not found: " + path);
            }
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(content);
            String prompt = (String) data.get("prompt");
            if (prompt == null || prompt.isBlank()) {
                throw new IllegalStateException("Prompt file missing 'prompt' key: " + path);
            }
            String version = (String) data.getOrDefault("version", "unknown");
            String description = (String) data.getOrDefault("description", "");
            log.info("Loaded prompt '{}' version={} description='{}'", name, version, description);
            return new PromptData(prompt.strip(), version, description);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load prompt: " + path, e);
        }
    }

    public record PromptData(String prompt, String version, String description) {}
}
