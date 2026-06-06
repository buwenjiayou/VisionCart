package com.visioncart.service.nlp;

import com.visioncart.api.dto.PriceRange;
import com.visioncart.api.dto.SearchFilter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RuleBasedNlpParser {
    private static final Pattern PRICE_RANGE = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:到|至|-|~)\\s*(\\d+(?:\\.\\d+)?)");
    private static final Pattern PRICE_MAX = Pattern.compile("(?:不超过|不超|最高|最多|以内|以下|低于|小于|少于|不大于)\\s*(\\d+(?:\\.\\d+)?)");
    private static final Pattern PRICE_MAX_REVERSE = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:以内|以下|不超过|不超|内|以下的)");
    private static final Pattern PRICE_MIN = Pattern.compile("(?:超过|高于|最少|至少|不低于|不下|大于|多于|不少于)\\s*(\\d+(?:\\.\\d+)?)");
    private static final Pattern RATING_MIN = Pattern.compile("(\\d(?:\\.\\d)?)\\s*(?:分|星|评分|好评)?\\s*(?:以上|及以上|>=|≥)");
    private static final String CN_DIGITS = "零一二两三四五六七八九十百千万亿";

    public ParsedFilter parse(String input) {
        String normalized = normalize(input);
        Double min = null;
        Double max = null;
        Matcher range = PRICE_RANGE.matcher(normalized);
        if (range.find()) {
            min = Double.parseDouble(range.group(1));
            max = Double.parseDouble(range.group(2));
            if (min > max) {
                double temp = min;
                min = max;
                max = temp;
            }
        } else {
            Matcher maxMatcher = PRICE_MAX.matcher(normalized);
            if (maxMatcher.find()) {
                max = Double.parseDouble(maxMatcher.group(1));
            } else {
                Matcher reverseMatcher = PRICE_MAX_REVERSE.matcher(normalized);
                if (reverseMatcher.find()) {
                    max = Double.parseDouble(reverseMatcher.group(1));
                } else {
                    Matcher minMatcher = PRICE_MIN.matcher(normalized);
                    if (minMatcher.find()) {
                        min = Double.parseDouble(minMatcher.group(1));
                    }
                }
            }
        }

        Double ratingMin = null;
        Matcher rating = RATING_MIN.matcher(normalized);
        if (rating.find()) {
            ratingMin = Math.min(5.0, Double.parseDouble(rating.group(1)));
        }

        List<String> platforms = new ArrayList<>();
        if (containsAny(normalized, "京东", "jd")) {
            platforms.add("京东");
        }
        if (containsAny(normalized, "淘宝", "某宝")) {
            platforms.add("淘宝");
        }
        if (containsAny(normalized, "天猫", "tmall", "猫超")) {
            platforms.add("天猫");
        }
        if (containsAny(normalized, "拼多多", "pdd", "拼夕夕")) {
            platforms.add("拼多多");
        }

        List<String> colors = new ArrayList<>();
        if (containsAny(normalized, "黑", "黑色", "黑武士")) {
            colors.add("黑色");
        }
        if (containsAny(normalized, "白", "白色", "米白", "象牙白")) {
            colors.add("白色");
        }
        if (containsAny(normalized, "蓝", "蓝色", "深蓝", "藏青")) {
            colors.add("蓝色");
        }
        if (containsAny(normalized, "红", "红色", "酒红")) {
            colors.add("红色");
        }
        if (containsAny(normalized, "绿", "绿色", "墨绿", "军绿", "草绿")) {
            colors.add("绿色");
        }
        if (containsAny(normalized, "黄", "黄色", "金黄", "姜黄")) {
            colors.add("黄色");
        }
        if (containsAny(normalized, "粉", "粉色", "粉红", "玫瑰")) {
            colors.add("粉色");
        }
        if (containsAny(normalized, "紫", "紫色", "薰衣草")) {
            colors.add("紫色");
        }
        if (containsAny(normalized, "灰", "灰色", "银灰", "深灰")) {
            colors.add("灰色");
        }
        if (containsAny(normalized, "金", "金色", "香槟金")) {
            colors.add("金色");
        }
        if (containsAny(normalized, "银", "银色", "银白")) {
            colors.add("银色");
        }

        // Negation detection (Bug #33) - detect "不要/除了/排除" patterns
        // Store negated terms in keyword with "!" prefix for downstream filtering
        List<String> negatedTerms = new ArrayList<>();
        java.util.regex.Matcher negMatcher = java.util.regex.Pattern.compile("(?:不要|除了|排除|不想|不喜欢)\\s*([^，。？！,\\.]+)").matcher(normalized);
        while (negMatcher.find()) {
            String term = negMatcher.group(1).trim();
            if (!term.isEmpty() && term.length() <= 20) {
                negatedTerms.add(term);
            }
        }

        Boolean selfOperated = containsAny(normalized, "自营", "官方", "旗舰") ? Boolean.TRUE : null;
        String sortBy = null;
        String sortOrder = "desc";
        if (containsAny(normalized, "便宜", "低价", "实惠", "划算", "性价比")) {
            sortBy = "price";
            sortOrder = "asc";
        }
        if (containsAny(normalized, "销量", "热门", "爆款", "畅销")) {
            sortBy = "sales";
        }
        if (containsAny(normalized, "好评", "评分", "口碑")) {
            sortBy = "rating";
        }

        // Build keyword with negation info for downstream use
        String keyword = negatedTerms.isEmpty() ? null : "!" + String.join(",", negatedTerms);
        SearchFilter filter = new SearchFilter(
                new PriceRange(min, max),
                platforms,
                selfOperated,
                colors,
                List.of(),
                ratingMin,
                sortBy,
                sortOrder,
                keyword
        );
        // 关键字段: 价格、平台、排序 — 只有命中这些才算完整，颜色/自营/评分不够
        boolean hasMeaningfulPrice = (min != null && min > 0) || (max != null && max > 0);
        boolean hasPlatform = !platforms.isEmpty();
        boolean hasSort = sortBy != null;
        boolean hasNegation = !negatedTerms.isEmpty();
        boolean hasColor = !colors.isEmpty();
        boolean complete = hasMeaningfulPrice || hasPlatform || hasSort || hasNegation || hasColor;
        return new ParsedFilter(filter, complete);
    }

    private String normalize(String input) {
        if (input == null) return "";
        String s = input.toLowerCase(Locale.ROOT)
                .replaceAll("(?i)rmb|￥|¥|元|块钱|块", "")
                .replaceAll("\\s+", "");
        // 先处理 "阿拉伯数字+中文单位" 的情况，如 "1万" → "10000"
        s = Pattern.compile("(\\d+(?:\\.\\d+)?)万").matcher(s)
                .replaceAll(mr -> String.valueOf((long)(Double.parseDouble(mr.group(1)) * 10000)));
        s = Pattern.compile("(\\d+(?:\\.\\d+)?)亿").matcher(s)
                .replaceAll(mr -> String.valueOf((long)(Double.parseDouble(mr.group(1)) * 100000000L)));
        s = Pattern.compile("(\\d+(?:\\.\\d+)?)千").matcher(s)
                .replaceAll(mr -> String.valueOf((long)(Double.parseDouble(mr.group(1)) * 1000)));
        // 数学表达式: "60的一半" → "30", "100的两倍" → "200"
        s = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*的\\s*一半").matcher(s)
                .replaceAll(mr -> String.valueOf(Double.parseDouble(mr.group(1)) / 2));
        s = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*的\\s*两倍").matcher(s)
                .replaceAll(mr -> String.valueOf(Double.parseDouble(mr.group(1)) * 2));
        s = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*的\\s*(\\d+(?:\\.\\d+)?)\\s*分之\\s*(\\d+(?:\\.\\d+)?)").matcher(s)
                .replaceAll(mr -> String.valueOf(Double.parseDouble(mr.group(1)) * Double.parseDouble(mr.group(3)) / Double.parseDouble(mr.group(2))));
        // 中文数字 → 阿拉伯数字（手动扫描）
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (CN_DIGITS.indexOf(c) >= 0) {
                int start = i;
                while (i < s.length() && CN_DIGITS.indexOf(s.charAt(i)) >= 0) {
                    i++;
                }
                String cnNum = s.substring(start, i);
                sb.append(parseChineseNumber(cnNum));
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private long parseChineseNumber(String cn) {
        long n = 0, current = 0;
        for (int i = 0; i < cn.length(); i++) {
            int d = chineseDigit(cn.charAt(i));
            if (d >= 0) {
                current = d;
            } else {
                int unit = chineseUnit(cn.charAt(i));
                if (unit >= 10000) {
                    n = (n + current) * unit;
                    if (n == 0) n = unit; // bare "万" or "亿"
                    current = 0;
                } else if (unit > 0) {
                    current = (current == 0 ? 1 : current) * unit;
                    n += current;
                    current = 0;
                }
            }
        }
        // 隐含单位: "一千五" → 1500, "三百五" → 350, "一万五" → 15000
        if (current > 0 && current < 10 && n > 0) {
            if (n % 10000 == 0) current *= 1000;
            else if (n % 1000 == 0) current *= 100;
            else if (n % 100 == 0) current *= 10;
        }
        return n + current;
    }

    private static int chineseDigit(char c) {
        return switch (c) {
            case '零' -> 0; // 零
            case '一' -> 1; // 一
            case '二' -> 2; // 二
            case '两' -> 2; // 两
            case '三' -> 3; // 三
            case '四' -> 4; // 四
            case '五' -> 5; // 五
            case '六' -> 6; // 六
            case '七' -> 7; // 七
            case '八' -> 8; // 八
            case '九' -> 9; // 九
            default -> -1;
        };
    }

    private static int chineseUnit(char c) {
        return switch (c) {
            case '十' -> 10;        // 十
            case '百' -> 100;       // 百
            case '千' -> 1000;      // 千
            case '万' -> 10000;     // 万
            case '亿' -> 100000000; // 亿
            default -> 0;
        };
    }

    private boolean containsAny(String text, String... tokens) {
        for (String token : tokens) {
            if (text.contains(token.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public record ParsedFilter(SearchFilter filter, boolean complete) {
    }
}
