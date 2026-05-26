package com.visioncart.service.nlp;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Prompt 注入防护工具类
 * 
 * 设计原则：
 * 1. 只过滤明确的指令模式，不单独过滤关键词
 * 2. 要求上下文完整（如"忽略"必须搭配"指令/规则"等）
 * 3. 保留正常购物语境中的词汇（如"忽略邮费"不过滤）
 */
public final class PromptSanitizer {

    private static final int MAX_INPUT_LENGTH = 500;

    /**
     * 注入模式列表 - 只匹配完整的指令模式
     * 
     * 优化策略：
     * - 要求明确的指令上下文（如"忽略...指令"而非单独的"忽略"）
     * - 要求角色切换的完整表达（如"你现在是..."而非单独的"你现在"）
     * - 保留正常购物语境词汇
     */
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
        // 中文忽略指令 - 要求完整的"忽略...指令/规则"结构
        Pattern.compile("(?i)忽略\\s*[以上面先前的]*\\s*(指令|规则|提示|要求|约束|限制)"),
        
        // 英文忽略指令
        Pattern.compile("(?i)ignore\\s+(all\\s+)?(previous|above|prior)\\s+(instructions?|rules?|prompts?)"),
        
        // 英文无视指令
        Pattern.compile("(?i)disregard\\s+(all\\s+)?(previous|above|prior)\\s+(instructions?|rules?|prompts?)"),
        
        // 角色切换 - 要求明确的角色定义
        Pattern.compile("(?i)you\\s+are\\s+now\\s+\\w+"),
        
        // 系统/助手伪装 - 要求冒号后的内容
        Pattern.compile("(?i)^\\s*system\\s*:.+$", Pattern.MULTILINE),
        Pattern.compile("(?i)^\\s*assistant\\s*:.+$", Pattern.MULTILINE),
        
        // DAN 越狱模式
        Pattern.compile("(?i)\\bDAN\\b\\s*(mode|jailbreak)?"),
        Pattern.compile("(?i)jailbreak"),
        
        // 新指令注入 - 要求冒号后的内容
        Pattern.compile("(?i)^\\s*new\\s+instructions?\\s*:.+$", Pattern.MULTILINE),
        
        // 覆盖指令
        Pattern.compile("(?i)override\\s+(all\\s+)?(previous|above)\\s+(instructions?|rules?|prompts?)"),
        
        // 中文忘记指令 - 要求完整的"忘记...指令/规则"结构
        Pattern.compile("(?i)忘记\\s*[你之前所有的]*\\s*(指令|规则|角色|设定)"),
        
        // 中文角色切换 - 要求明确的角色定义
        Pattern.compile("(?i)你现在是\\s*[^\\s，。？！]+"),
        
        // 调试模式
        Pattern.compile("(?i)进入\\s*[开发调试自由]+\\s*模式"),
        
        // 角色扮演 - 要求明确的扮演目标
        Pattern.compile("(?i)pretend\\s+(you\\s+are|to\\s+be)\\s+\\w+"),
        Pattern.compile("(?i)act\\s+as\\s+(if\\s+)?(a\\s+)?(you\\s+)?(are|were)?\\s*\\w+")
    );

    private static final Pattern TRIPLE_BACKTICK = Pattern.compile("```");
    private static final Pattern SPECIAL_INJECTION_CHARS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]");

    private PromptSanitizer() {}

    /**
     * 清理输入文本，移除潜在的注入内容
     * 
     * @param input 原始输入
     * @return 清理后的文本
     */
    public static String sanitize(String input) {
        if (input == null) return "";
        
        // 1. 移除控制字符
        String cleaned = SPECIAL_INJECTION_CHARS.matcher(input).replaceAll("");
        
        // 2. 移除代码块标记（防止通过代码块注入）
        cleaned = TRIPLE_BACKTICK.matcher(cleaned).replaceAll("");
        
        // 3. 长度截断
        if (cleaned.length() > MAX_INPUT_LENGTH) {
            cleaned = cleaned.substring(0, MAX_INPUT_LENGTH);
        }
        
        // 4. 过滤注入模式
        for (Pattern pattern : INJECTION_PATTERNS) {
            cleaned = pattern.matcher(cleaned).replaceAll("[已过滤]");
        }
        
        return cleaned.trim();
    }

    /**
     * 检测输入是否包含注入模式
     * 
     * @param input 输入文本
     * @return 是否包含注入
     */
    public static boolean containsInjection(String input) {
        if (input == null || input.isBlank()) return false;
        
        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(input).find()) {
                return true;
            }
        }
        return false;
    }
}
