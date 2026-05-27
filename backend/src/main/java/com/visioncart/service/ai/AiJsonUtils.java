package com.visioncart.service.ai;

public final class AiJsonUtils {

    private AiJsonUtils() {
    }

    public static String extractFirstJsonObject(String content) {
        String text = content == null ? "" : stripMarkdownFence(content.trim());
        int start = text.indexOf('{');
        if (start < 0) {
            return text;
        }
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            if (ch == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return text.substring(start);
    }

    public static double clampConfidence(double value, double fallback) {
        double confidence = Double.isFinite(value) ? value : fallback;
        return Math.max(0, Math.min(1, confidence));
    }

    private static String stripMarkdownFence(String content) {
        if (!content.startsWith("```")) {
            return content;
        }
        int firstNewline = content.indexOf('\n');
        int lastFence = content.lastIndexOf("```");
        if (firstNewline >= 0 && lastFence > firstNewline) {
            return content.substring(firstNewline + 1, lastFence).trim();
        }
        return content;
    }
}
