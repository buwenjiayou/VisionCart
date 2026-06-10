package com.visioncart.service.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class PromptManifestTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern VERSION = Pattern.compile("^version:\\s*\"?([^\"\\r\\n]+)\"?\\s*$");
    private static final Pattern DESCRIPTION = Pattern.compile("^description:\\s*\"?([^\"\\r\\n]+)\"?\\s*$");

    @Test
    void promptManifestMatchesCurrentPromptFiles() throws Exception {
        Path promptDir = promptDirectory();
        Map<String, PromptSnapshot> actual = Files.list(promptDir)
                .filter(path -> path.getFileName().toString().endsWith(".yml"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .map(this::snapshot)
                .collect(Collectors.toMap(PromptSnapshot::name, snapshot -> snapshot,
                        (a, b) -> a, LinkedHashMap::new));

        List<PromptManifestEntry> manifest = manifest();
        Map<String, PromptManifestEntry> expected = manifest.stream()
                .collect(Collectors.toMap(PromptManifestEntry::name, entry -> entry,
                        (a, b) -> a, LinkedHashMap::new));

        assertThat(actual.keySet()).containsExactlyElementsOf(expected.keySet());

        actual.forEach((name, snapshot) -> {
            PromptManifestEntry entry = expected.get(name);
            assertThat(snapshot.version()).as(name + " version").isNotBlank();
            assertThat(snapshot.description()).as(name + " description").isNotBlank();
            assertThat(snapshot.prompt()).as(name + " prompt").isNotBlank();
            assertThat(snapshot.version()).as(name + " manifest version").isEqualTo(entry.version());
            assertThat(snapshot.contentSha256()).as(name + " prompt hash").isEqualTo(entry.contentSha256());
            assertThat(snapshot.placeholderCount()).as(name + " %%s placeholder count")
                    .isEqualTo(entry.placeholderCount());
        });
    }

    private List<PromptManifestEntry> manifest() throws Exception {
        try (var input = getClass().getClassLoader()
                .getResourceAsStream("ai-golden/prompt-manifest.json")) {
            assertThat(input).as("ai-golden/prompt-manifest.json").isNotNull();
            return OBJECT_MAPPER.readValue(input, new TypeReference<>() {});
        }
    }

    private Path promptDirectory() {
        Path backendLocal = Path.of("src/main/resources/prompts");
        if (Files.isDirectory(backendLocal)) {
            return backendLocal;
        }
        Path repoRoot = Path.of("backend/src/main/resources/prompts");
        assertThat(Files.isDirectory(repoRoot)).as("prompt directory").isTrue();
        return repoRoot;
    }

    private PromptSnapshot snapshot(Path path) {
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            String name = path.getFileName().toString().replaceFirst("\\.yml$", "");
            String version = firstMatch(lines, VERSION);
            String description = firstMatch(lines, DESCRIPTION);
            String prompt = promptBlock(lines);
            return new PromptSnapshot(name, version, description, prompt, sha256(prompt), count(prompt, "%s"));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read prompt manifest snapshot for " + path, e);
        }
    }

    private String firstMatch(List<String> lines, Pattern pattern) {
        for (String line : lines) {
            var matcher = pattern.matcher(line);
            if (matcher.matches()) {
                return matcher.group(1);
            }
        }
        return "";
    }

    private String promptBlock(List<String> lines) {
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).matches("^prompt:\\s*\\|\\s*$")) {
                start = i + 1;
                break;
            }
        }
        assertThat(start).as("prompt block").isGreaterThanOrEqualTo(0);
        return lines.subList(start, lines.size()).stream()
                .map(line -> line.startsWith("  ") ? line.substring(2) : line)
                .collect(Collectors.joining("\n"))
                .strip();
    }

    private String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : hash) {
            out.append(String.format("%02x", b));
        }
        return out.toString();
    }

    private int count(String value, String needle) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private record PromptManifestEntry(
            String name,
            String version,
            String description,
            String contentSha256,
            int placeholderCount
    ) {}

    private record PromptSnapshot(
            String name,
            String version,
            String description,
            String prompt,
            String contentSha256,
            int placeholderCount
    ) {}
}
