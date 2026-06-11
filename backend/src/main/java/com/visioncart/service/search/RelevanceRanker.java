package com.visioncart.service.search;

import com.visioncart.api.dto.ProductCard;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class RelevanceRanker {
    public enum RelevanceTier {
        STRONG,
        SAFE_FILL,
        REJECTED
    }

    private static final Map<String, List<String>> CONTRADICTORY = Map.ofEntries(
            // 属性级矛盾
            Map.entry("电动", List.of("手动")),
            Map.entry("手动", List.of("电动")),
            Map.entry("无线", List.of("有线")),
            Map.entry("有线", List.of("无线", "蓝牙")),
            Map.entry("自动", List.of("手动")),
            Map.entry("智能", List.of("手动")),
            // 规格级矛盾 — 头数/尺寸等互斥修饰词
            Map.entry("单头", List.of("双头", "两头", "2头", "三头", "3头")),
            Map.entry("双头", List.of("单头", "一头", "1头", "三头", "3头")),
            Map.entry("三头", List.of("单头", "一头", "1头", "双头", "两头", "2头")),
            Map.entry("大号", List.of("小号", "迷你", "mini")),
            Map.entry("小号", List.of("大号")),
            Map.entry("男", List.of("女")),
            Map.entry("女", List.of("男")),
            Map.entry("男款", List.of("女款")),
            Map.entry("女款", List.of("男款")),
            Map.entry("低帮", List.of("高帮")),
            Map.entry("高帮", List.of("低帮")),
            Map.entry("长袖", List.of("短袖")),
            Map.entry("短袖", List.of("长袖")),
            Map.entry("加厚", List.of("薄款")),
            Map.entry("薄款", List.of("加厚")),
            Map.entry("保温", List.of("制冷")),
            Map.entry("充电", List.of("电池")),
            // 品类级矛盾 — 主品 vs 配件/消耗品
            Map.entry("剃须刀", List.of("剃须膏", "剃须泡沫", "剃须啫喱", "刀片", "刀头", "须后水")),
            Map.entry("吹风机", List.of("风嘴", "风罩", "支架")),
            Map.entry("电动牙刷", List.of("牙膏", "牙线", "漱口水", "刷头")),
            Map.entry("冲牙器", List.of("牙膏", "牙线", "漱口水")),
            Map.entry("手机", List.of("手机壳", "手机膜", "手机支架", "手机袋", "手机套", "手机指环")),
            Map.entry("耳机", List.of("耳机套", "耳帽", "耳塞", "耳机壳", "耳机架")),
            Map.entry("手表", List.of("表带", "表壳", "表膜", "表链")),
            Map.entry("智能手表", List.of("表带", "表壳", "表膜", "充电底座")),
            Map.entry("智能手环", List.of("表带", "表壳", "表膜", "充电底座")),
            Map.entry("相机", List.of("相机包", "相机膜", "镜头盖", "UV镜", "滤镜")),
            Map.entry("笔记本", List.of("笔记本支架", "笔记本包", "键盘膜", "散热底座")),
            Map.entry("平板", List.of("平板壳", "平板膜", "平板支架", "触控笔")),
            Map.entry("显示器", List.of("显示器支架", "显示器挂灯", "屏幕清洁")),
            Map.entry("键盘", List.of("键帽", "掌托", "拔键器", "防尘罩")),
            Map.entry("鼠标", List.of("鼠标垫", "鼠标脚贴")),
            Map.entry("路由器", List.of("网线", "光纤")),
            Map.entry("无人机", List.of("螺旋桨", "保护罩")),
            Map.entry("投影仪", List.of("幕布", "支架", "吊架")),
            Map.entry("打印机", List.of("墨盒", "硒鼓", "碳粉", "打印纸")),
            Map.entry("运动鞋", List.of("鞋垫", "鞋带", "鞋油", "鞋刷")),
            Map.entry("皮鞋", List.of("鞋垫", "鞋油", "鞋刷", "鞋撑")),
            Map.entry("篮球鞋", List.of("鞋垫", "鞋带", "鞋油")),
            Map.entry("跑鞋", List.of("鞋垫", "鞋带", "鞋油")),
            Map.entry("行李箱", List.of("箱套", "TSA锁", "打包带", "万向轮")),
            Map.entry("双肩包", List.of("防雨罩", "挂扣")),
            Map.entry("电饭煲", List.of("量杯", "饭勺", "蒸笼")),
            Map.entry("空气炸锅", List.of("炸锅纸", "锡纸", "烤盘", "油纸")),
            Map.entry("咖啡机", List.of("咖啡豆", "咖啡粉", "滤纸", "清洁片")),
            Map.entry("烤箱", List.of("烤盘", "锡纸", "油纸", "烘焙纸")),
            Map.entry("吸尘器", List.of("滤芯", "滤网", "尘袋", "吸头")),
            Map.entry("扫地机器人", List.of("抹布", "边刷", "滚刷", "滤芯")),
            Map.entry("洗地机", List.of("滚刷", "滤芯", "清洁液")),
            Map.entry("净水器", List.of("滤芯", "PE管", "接头")),
            Map.entry("空气净化器", List.of("滤芯", "滤网")),
            Map.entry("加湿器", List.of("滤芯", "清洁剂")),
            Map.entry("空调", List.of("空调罩", "空调支架", "过滤网", "清洗剂")),
            Map.entry("冰箱", List.of("冰箱贴", "冰箱收纳", "冰箱除味")),
            Map.entry("洗衣机", List.of("洗衣液", "洗衣粉", "柔顺剂", "清洁剂")),
            Map.entry("电视", List.of("电视挂架", "遥控器", "电视罩")),
            Map.entry("奶瓶", List.of("奶嘴", "奶瓶刷", "奶瓶架", "温奶器")),
            Map.entry("纸尿裤", List.of("湿巾", "护臀膏", "隔尿垫")),
            Map.entry("婴儿车", List.of("蚊帐", "雨罩", "凉席")),
            Map.entry("吉他", List.of("琴弦", "琴包", "拨片", "变调夹", "背带")),
            Map.entry("钢琴", List.of("琴凳", "琴罩", "节拍器")),
            Map.entry("口红", List.of("唇线笔", "唇刷", "唇膜", "卸妆")),
            Map.entry("粉底液", List.of("美妆蛋", "粉底刷", "妆前乳")),
            Map.entry("面膜", List.of("面膜碗", "面膜刷", "面膜贴")),
            Map.entry("洗面奶", List.of("洁面仪", "起泡网", "洗脸巾")),
            Map.entry("项链", List.of("项链盒", "首饰盒", "擦银布")),
            Map.entry("戒指", List.of("戒指盒", "首饰盒", "量指圈")),
            Map.entry("墨镜", List.of("眼镜盒", "眼镜布", "眼镜链")),
            Map.entry("咖啡", List.of("咖啡杯", "咖啡壶", "咖啡机", "奶精")),
            Map.entry("茶叶", List.of("茶壶", "茶杯", "茶盘", "茶具")),
            Map.entry("跑步机", List.of("润滑油", "减震垫", "安全锁")),
            Map.entry("自行车", List.of("车灯", "车锁", "打气筒", "头盔")),
            Map.entry("瑜伽垫", List.of("瑜伽砖", "瑜伽带", "瑜伽服")),
            Map.entry("电钻", List.of("钻头", "批头", "工具箱")),
            Map.entry("猫粮", List.of("猫碗", "猫罐头", "化毛膏")),
            Map.entry("狗粮", List.of("狗碗", "狗罐头", "磨牙棒")),
            Map.entry("行车记录仪", List.of("存储卡", "支架", "降压线"))
    );

    public List<ProductCard> rank(List<ProductCard> products, Map<String, String> attributes) {
        SearchIntent intent = SearchIntent.from(attributes, null);
        return rank(products, intent);
    }

    public List<ProductCard> rank(List<ProductCard> products, SearchIntent intent) {
        return products.stream()
                .sorted(Comparator.comparingDouble((ProductCard product) -> score(product, intent)).reversed())
                .toList();
    }

    public List<ProductCard> withSimilarity(List<ProductCard> products, SearchIntent intent) {
        List<ProductCard> scored = new ArrayList<>();
        for (ProductCard product : products) {
            // 每个商品独立推断品类 code（从自身标题），不复制 intent 的 code。
            // 标题无法归一化时 code=""，在 tier() 中会被意图品类过滤掉。
            String code = CategoryNormalizer.normalize("", List.of(product.title()));
            String role = CategoryNormalizer.inferRole(product.title(), code);
            ProductCard classified = product.withRole(code, role);
            scored.add(withSimilarity(classified, finalSimilarity(classified, intent)));
        }
        return scored;
    }

    public RelevanceTier tier(ProductCard product, SearchIntent intent) {
        if (product == null || product.title() == null || product.title().isBlank()) {
            return RelevanceTier.REJECTED;
        }
        if (PowerBankSearchRules.isPowerBank(intent)) {
            PowerBankProductClassifier.Type powerBankType = PowerBankProductClassifier.classify(product.title());
            if (powerBankType == PowerBankProductClassifier.Type.NON_TARGET) {
                return RelevanceTier.REJECTED;
            }
            if (powerBankType == PowerBankProductClassifier.Type.RELATED_BUT_NOT_TARGET) {
                return RelevanceTier.SAFE_FILL;
            }
        }
        if (!intent.hasSpecificSignals()) {
            return RelevanceTier.SAFE_FILL;
        }
        String title = product.title().toLowerCase(Locale.ROOT);
        boolean accessoryIntent = intent.targetsAccessory();

        // ====== 结构化角色判断（优先于标题规则） ======
        String intentCode = intent.mainCategoryCode();
        String productCode = product.mainCategoryCode();
        String productRole = product.productRole();

        // 品类不匹配：搜索杯子(cup)但商品是手机(phone) → REJECTED
        if (!intentCode.isEmpty() && !productCode.isEmpty()
                && !intentCode.equals(productCode)
                // 配件可能跨品类（如"手机支架"的 categoryCode 可能不是 phone）
                && !accessoryIntent) {
            // 同属一个品类组时放宽（如耳机和音箱都是 audio）
            if (!inSameCategoryGroup(intentCode, productCode)) {
                return RelevanceTier.REJECTED;
            }
        }
        // 商品标题无法归一化（code=""）但意图有明确品类 → 大概率不相关，直接拒绝。
        // 避免"肩颈按摩仪"在搜"鼠标"时因 code="" 跳过品类检查而漏网。
        if (!intentCode.isEmpty() && productCode.isEmpty() && !accessoryIntent) {
            return RelevanceTier.REJECTED;
        }

        // 角色冲突：搜主品(intentRole=main)但商品是配件(productRole=accessory) → REJECTED
        // 但用户搜索的就是配件时（如"手机壳"），允许配件商品通过
        if (!accessoryIntent && "main".equals(intent.intentRole()) && "accessory".equals(productRole)) {
            return RelevanceTier.REJECTED;
        }
        // 搜配件(intentRole=accessory)但商品是主品 → 也 REJECTED
        if ("accessory".equals(intent.intentRole()) && "main".equals(productRole)) {
            return RelevanceTier.REJECTED;
        }

        // ====== 标题规则（结构化字段不足时的兜底） ======
        // 注意：不用 hasExcludedTerms(title, category) 因为当 category="手机" 时，
        // 会错误地排除 "iPhone 15"（"Phone" 匹配 "手机"）。
        // 只用 coreProduct 做排除判断。
        if (hasContradictoryTerms(product, intent)
                || (!accessoryIntent && !intent.coreProduct().isBlank() && SearchTextUtils.hasExcludedTerms(title, intent.coreProduct()))
                || (!accessoryIntent && SearchTextUtils.hasAccessoryConflict(title, intent.coreProduct()))) {
            return RelevanceTier.REJECTED;
        }
        // 主商品：品牌冲突直接拒绝。
        // 配件：品牌是"适用品牌"而非"制造商品牌"，不因 product.brand≠expected 就拒绝；
        //       hasConflictingBrand() 已内置"文本含目标品牌则不冲突"逻辑，此处对配件额外放行。
        if (intent.hasReliableBrand() && !accessoryIntent && BrandMatcher.hasConflictingBrand(product, intent.brand())) {
            return RelevanceTier.REJECTED;
        }

        double similarity = similarity(product, intent);
        boolean brandMatch = !intent.brand().isBlank()
                && BrandMatcher.productMatchesExpectedBrand(product, intent.brand());

        // 配件+可靠品牌：标题/文本必须出现目标品牌（适用品牌语义），否则拒绝。
        // 例：搜 iQOO 手机壳 → 标题必须含 iQOO；壳的制造商品牌可以是倍思、闪魔等。
        if (intent.hasReliableBrand() && accessoryIntent && !brandMatch) {
            return RelevanceTier.REJECTED;
        }

        boolean exactMatch = matchCount(searchable(product), intent.exactTerms()) > 0;
        boolean coreMatch = matchesCoreProductFamily(title, intent);
        boolean keywordMatch = matchesAny(title, intent.keywords());
        boolean categoryMatch = contains(title, intent.category());

        // 结构化品类+角色匹配：品类 code 相同且角色一致
        boolean categoryCodeMatch = !intentCode.isEmpty() && intentCode.equals(productCode);
        boolean roleMatch = categoryCodeMatch
                && (("main".equals(intent.intentRole()) && "main".equals(productRole))
                    || ("accessory".equals(intent.intentRole()) && "accessory".equals(productRole)));
        // 品类 code 匹配但角色不冲突（如搜主品时，同品类商品都算相关）
        boolean categoryRelated = categoryCodeMatch && !("accessory".equals(intent.intentRole()) && "main".equals(productRole));

        if ((intent.hasReliableBrand() && brandMatch && (exactMatch || coreMatch || keywordMatch || similarity >= 0.35))
                || (exactMatch && (brandMatch || coreMatch || keywordMatch || categoryMatch))
                || (coreMatch && keywordMatch && similarity >= 0.36)
                || (roleMatch && (keywordMatch || coreMatch || similarity >= 0.30))
                || similarity >= 0.50) {
            return RelevanceTier.STRONG;
        }

        if (coreMatch
                || keywordMatch
                || categoryMatch
                || (exactMatch && similarity >= 0.20)
                || (!intent.hasReliableBrand() && brandMatch && similarity >= 0.18)
                || (roleMatch && similarity >= 0.10)
                || categoryRelated
                || (matchesAccessoryPattern(title, intent))
                || similarity >= 0.25) {
            return RelevanceTier.SAFE_FILL;
        }

        return RelevanceTier.REJECTED;
    }

    /** 判断两个品类 code 是否属于同一组（如耳机/音箱都是 audio） */
    private boolean inSameCategoryGroup(String code1, String code2) {
        if (code1.equals(code2)) return true;
        // 音频组
        if (Set.of("headphone", "speaker").contains(code1) && Set.of("headphone", "speaker").contains(code2)) return true;
        // 电脑组
        if (Set.of("laptop", "tablet", "monitor", "keyboard", "mouse").contains(code1)
                && Set.of("laptop", "tablet", "monitor", "keyboard", "mouse").contains(code2)) return true;
        // 鞋类组
        if (Set.of("shoe").contains(code1) && Set.of("shoe").contains(code2)) return true;
        return false;
    }

    public boolean isRelevant(ProductCard product, SearchIntent intent) {
        return tier(product, intent) != RelevanceTier.REJECTED;
    }

    public double similarity(ProductCard product, SearchIntent intent) {
        String title = product.title();
        String searchable = searchable(product);
        double score = 0.0;

        if (!intent.brand().isBlank()) {
            if (BrandMatcher.productMatchesExpectedBrand(product, intent.brand())) {
                score += intent.hasReliableBrand() ? 0.25 : 0.15;
            } else if (intent.hasReliableBrand() && BrandMatcher.hasConflictingBrand(product, intent.brand())) {
                score -= 0.45;
            }
        }

        int exactMatches = matchCount(searchable, intent.exactTerms());
        if (exactMatches > 0) {
            score += Math.min(0.45, 0.32 + (exactMatches - 1) * 0.08);
        } else if (!intent.exactTerms().isEmpty()) {
            score -= 0.10;
        }

        score += keywordScore(title, intent.keywords());

        if (contains(title, intent.category()) && intent.category().length() >= 2) {
            score += 0.16;
        } else if (contains(title, intent.coreProduct()) && intent.coreProduct().length() >= 2) {
            score += 0.14;
        }

        int descriptiveMatches = matchCount(title, intent.descriptiveTerms());
        score += Math.min(0.12, descriptiveMatches * 0.04);

        // 修饰词软惩罚：类目或核心产品含"电动"但标题不含 → 降分但不排除
        String modifier = SearchTextUtils.extractModifier(intent.category());
        if (modifier.isBlank()) {
            modifier = SearchTextUtils.extractModifier(intent.coreProduct());
        }
        if (!modifier.isBlank() && !contains(title, modifier)) {
            score -= 0.15;
        }

        return Math.max(0.0, Math.min(1.0, score));
    }

    private double finalSimilarity(ProductCard product, SearchIntent intent) {
        double base = similarity(product, intent);
        return switch (tier(product, intent)) {
            case STRONG -> Math.min(1.0, 0.70 + base * 0.30);
            case SAFE_FILL -> Math.min(0.69, 0.35 + base * 0.30);
            case REJECTED -> Math.min(0.20, base * 0.20);
        };
    }

    private double score(ProductCard product, SearchIntent intent) {
        double score = product.similarity() * 100;
        if (intent.hasReliableBrand() && BrandMatcher.productMatchesExpectedBrand(product, intent.brand())) {
            score += 20;
        }
        if (!intent.exactTerms().isEmpty() && matchCount(searchable(product), intent.exactTerms()) > 0) {
            score += 25;
        }
        if (product.selfOperated()) {
            score += 8;
        }
        score += Math.min(8, Math.log10(Math.max(1, product.sales())) * 2);
        if (!"none".equals(product.ratingSource())) {
            score += Math.min(5, product.rating());
        }
        return score;
    }

    private boolean contains(String text, String token) {
        return token != null
                && !token.isBlank()
                && text != null
                && text.toLowerCase(Locale.ROOT).contains(token.toLowerCase(Locale.ROOT));
    }

    private int matchCount(String text, List<String> tokens) {
        int matches = 0;
        for (String token : tokens) {
            if (SearchTextUtils.containsNormalized(text, token)) {
                matches++;
            }
        }
        return matches;
    }

    private double keywordScore(String text, List<String> keywords) {
        double total = 0.0;
        double bestFuzzy = 0.0;
        String normalizedText = SearchTextUtils.normalizeForMatch(text);
        for (String keyword : keywords) {
            String normalizedKeyword = SearchTextUtils.normalizeForMatch(keyword);
            if (normalizedKeyword.isBlank()) {
                continue;
            }
            if (normalizedText.contains(normalizedKeyword)) {
                total += 0.12;
            } else {
                bestFuzzy = Math.max(bestFuzzy, 0.18 * bigramCoverage(normalizedText, normalizedKeyword));
            }
        }
        return Math.min(0.35, total) + bestFuzzy;
    }

    private boolean matchesCoreProductFamily(String title, SearchIntent intent) {
        if (intent.coreProduct().isBlank()) {
            return false;
        }
        // 直接包含核心词（如"马克杯"含"杯子"）
        if (contains(title, intent.coreProduct())) {
            return true;
        }
        // 同组词匹配（如"保温杯"是"杯子"的同组词）
        if (SearchTextUtils.titleContainsProductFamily(title, intent.coreProduct())) {
            return true;
        }
        // 词根匹配：提取核心词中最短的 CORE_PRODUCT_TERM（如"杯子"→"杯"）
        // 解决"陶瓷杯"含"杯"但不含"杯子"的问题
        String root = extractCoreRoot(intent.coreProduct());
        if (!root.isEmpty() && !root.equals(intent.coreProduct()) && contains(title, root)) {
            return true;
        }
        return false;
    }

    /** 从核心产品词中提取最短的 CORE_PRODUCT_TERM 子串。"杯子"→"杯"，"保温杯"→"杯" */
    private String extractCoreRoot(String coreProduct) {
        String lower = coreProduct.toLowerCase(Locale.ROOT);
        String shortest = "";
        for (String term : SearchTextUtils.getCoreProductTerms()) {
            String termLower = term.toLowerCase(Locale.ROOT);
            if (lower.contains(termLower) && (shortest.isEmpty() || termLower.length() < shortest.length())) {
                shortest = termLower;
            }
        }
        return shortest;
    }

    private boolean matchesAny(String title, List<String> values) {
        for (String value : values) {
            if (contains(title, value)) {
                return true;
            }
            String core = SearchTextUtils.coreProductToken(value);
            if (!core.isBlank() && SearchTextUtils.titleContainsProductFamily(title, core)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 配件后缀模糊匹配：标题以"核心产品词+配件后缀单字"开头时匹配。
     * 解决 coreProductToken("杯套")="杯套" 而非 "杯" 导致的匹配失败。
     * 例：搜"杯套" → coreProduct="杯" → "杯套xxx"以"杯"+"套"开头 → 匹配
     *     "水杯保护套"不以"杯"开头 → 不匹配（由 roleMatch 处理）
     *     "马克杯"以"杯"开头但"子"不是配件后缀 → 不匹配
     */
    private boolean matchesAccessoryPattern(String title, SearchIntent intent) {
        String core = intent.coreProduct();
        if (core.isBlank() || core.length() < 2) return false;
        String coreLower = core.toLowerCase(Locale.ROOT);
        if (!title.startsWith(coreLower) || title.length() <= coreLower.length()) return false;
        char nextChar = title.charAt(coreLower.length());
        return SearchTextUtils.ACCESSORY_SUFFIX_CHARS.indexOf(nextChar) >= 0;
    }

    private double bigramCoverage(String normalizedText, String normalizedKeyword) {
        if (normalizedKeyword.length() < 4) {
            return 0.0;
        }
        int total = 0;
        int matched = 0;
        for (int i = 0; i < normalizedKeyword.length() - 1; i++) {
            String token = normalizedKeyword.substring(i, i + 2);
            if (token.matches("[a-z0-9]{2}")) {
                continue;
            }
            total++;
            if (normalizedText.contains(token)) {
                matched++;
            }
        }
        return total == 0 ? 0.0 : (double) matched / total;
    }

    private String searchable(ProductCard product) {
        return String.join(" ",
                product.title() == null ? "" : product.title(),
                product.brand() == null ? "" : product.brand(),
                product.shopName() == null ? "" : product.shopName());
    }

    private ProductCard withSimilarity(ProductCard product, double similarity) {
        return new ProductCard(
                product.id(),
                product.title(),
                product.imageUrl(),
                product.price(),
                product.originalPrice(),
                product.platform(),
                product.selfOperated(),
                product.shopName(),
                product.rating(),
                product.sales(),
                similarity,
                product.tags(),
                product.detailUrl(),
                product.brand(),
                product.ratingSource(),
                product.salesLabel(),
                product.mainCategoryCode(),
                product.productRole()
        );
    }

    private boolean hasContradictoryTerms(ProductCard product, SearchIntent intent) {
        String title = product.title() == null ? "" : product.title().toLowerCase(Locale.ROOT);
        List<String> intentTerms = new ArrayList<>();
        intentTerms.addAll(intent.keywords());
        intentTerms.addAll(intent.descriptiveTerms());
        intentTerms.addAll(intent.exactTerms());
        if (!intent.category().isBlank()) {
            intentTerms.add(intent.category());
        }
        if (!intent.coreProduct().isBlank()) {
            intentTerms.add(intent.coreProduct());
        }

        for (String term : intentTerms) {
            String lower = term.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, List<String>> entry : CONTRADICTORY.entrySet()) {
                // 精确匹配：intent term 必须是矛盾 key 本身，而非包含关系
                // "单头剃须刀" 包含 "单头" → 匹配
                // "自动铅笔" 包含 "自动" → 不匹配（"自动铅笔"是产品名，不是"自动"修饰词）
                if (lower.equals(entry.getKey()) || lower.contains(entry.getKey() + "的")
                        || lower.endsWith(entry.getKey())) {
                    for (String opposite : entry.getValue()) {
                        if (title.contains(opposite)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
}
