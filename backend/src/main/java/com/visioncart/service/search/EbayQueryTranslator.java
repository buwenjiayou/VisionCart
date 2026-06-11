package com.visioncart.service.search;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
public class EbayQueryTranslator {
    private static final Logger log = LoggerFactory.getLogger(EbayQueryTranslator.class);
    private static final int MAX_QUERY_LENGTH = 80;
    private static final Duration CACHE_TTL = Duration.ofHours(6);

    private final ObjectProvider<ChatClient.Builder> chatClientBuilder;
    private final ConcurrentHashMap<String, CachedQuery> cache = new ConcurrentHashMap<>();

    public EbayQueryTranslator(ObjectProvider<ChatClient.Builder> chatClientBuilder) {
        this.chatClientBuilder = chatClientBuilder;
    }

    public String translateForEbay(String query) {
        String source = sanitizeSource(query);
        if (source.isBlank()) {
            return "shopping item";
        }
        String cacheKey = source.toLowerCase(Locale.ROOT);
        CachedQuery cached = cache.get(cacheKey);
        if (cached != null && cached.expiresAtMillis > System.currentTimeMillis()) {
            return cached.query;
        }

        String translated = translateWithLlm(source);
        if (translated.isBlank()) {
            translated = localFallback(source);
        }
        translated = sanitizeEnglishQuery(translated);
        if (translated.isBlank()) {
            translated = sanitizeEnglishQuery(localFallback(source));
        }
        if (translated.isBlank()) {
            translated = source;
        }
        cache.put(cacheKey, new CachedQuery(translated, System.currentTimeMillis() + CACHE_TTL.toMillis()));
        return translated;
    }

    private String translateWithLlm(String query) {
        try {
            ChatClient.Builder builder = chatClientBuilder.getIfAvailable();
            if (builder == null) {
                return "";
            }
            String content = CompletableFuture.supplyAsync(() -> builder.build()
                            .prompt()
                            .system("""
                                    You translate Chinese shopping search queries for eBay US.
                                    Output only one short English marketplace search query.
                                    Preserve product type, model, brand, capacity, material, color, and important appearance phrases.
                                    Do not explain. Do not output JSON. Do not add brands or attributes not present in the input.
                                    """)
                            .user("Translate this eBay search query to English: " + query)
                            .call()
                            .content())
                    .get(3, TimeUnit.SECONDS);
            return sanitizeEnglishQuery(content);
        } catch (Exception e) {
            log.warn("eBay query LLM translation failed for '{}': {}", query, e.getMessage());
            return "";
        }
    }

    String sanitizeEnglishQuery(String raw) {
        if (raw == null) return "";
        String text = raw.strip();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json|text)?\\s*", "")
                    .replaceFirst("\\s*```$", "")
                    .strip();
        }
        java.util.regex.Matcher queryField = java.util.regex.Pattern
                .compile("\"query\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(text);
        if (queryField.find()) {
            text = queryField.group(1).strip();
        }
        int newline = text.indexOf('\n');
        if (newline >= 0) {
            text = text.substring(0, newline).strip();
        }
        text = text.replaceAll("^[\"'`]+|[\"'`]+$", "");
        text = text.replaceAll("[{}\\[\\]:;]+", " ");
        text = text.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\s+\\-./]", " ");
        text = text.replaceAll("\\s+", " ").strip();
        if (text.length() > MAX_QUERY_LENGTH) {
            text = text.substring(0, MAX_QUERY_LENGTH).strip();
        }
        return text;
    }

    String localFallback(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        Map<String, String> replacements = new LinkedHashMap<>();
        replacements.put("透明外壳", "transparent shell");
        replacements.put("露电路板", "visible circuit board");
        replacements.put("黄色led指示灯", "yellow LED indicator");
        replacements.put("led指示灯", "LED indicator");
        replacements.put("黄黑配色", "yellow black");
        replacements.put("充电宝", "power bank");
        replacements.put("移动电源", "power bank");
        replacements.put("便携充电器", "portable charger");
        replacements.put("鼠标", "mouse");
        replacements.put("无线", "wireless");
        replacements.put("有线", "wired");
        replacements.put("蓝牙", "bluetooth");
        replacements.put("键盘", "keyboard");
        replacements.put("耳机", "earbuds");
        replacements.put("手机壳", "phone case");
        replacements.put("手机", "phone");
        replacements.put("剃须刀", "electric shaver");
        replacements.put("透明", "transparent");
        replacements.put("黑色", "black");
        replacements.put("白色", "white");
        replacements.put("黄色", "yellow");
        replacements.put("红色", "red");
        replacements.put("蓝色", "blue");
        replacements.put("绿色", "green");
        replacements.put("快充", "fast charging");
        replacements.put("自带线", "built in cable");
        replacements.put("数显", "digital display");
        replacements.put("电量显示", "battery display");

        String result = lower;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            result = result.replace(entry.getKey(), " " + entry.getValue() + " ");
        }
        result = result.replaceAll("(?i)(\\d+)\\s*ah", "$1Ah")
                .replaceAll("(?i)(\\d+)\\s*mah", "$1mAh")
                .replaceAll("[\\p{IsHan}]", " ")
                .replaceAll("\\s+", " ")
                .strip();
        return result;
    }

    private String sanitizeSource(String query) {
        return StringUtils.defaultString(query).replaceAll("\\s+", " ").strip();
    }

    private record CachedQuery(String query, long expiresAtMillis) {
    }
}
