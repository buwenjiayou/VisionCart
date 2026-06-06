package com.visioncart.service.search;

import java.util.Locale;
import java.util.Map;

/**
 * 品类归一化：将宽泛类目/关键词映射到结构化 mainCategoryCode。
 * 解决 VLM 返回"餐具水具"而非"杯子"时，过滤逻辑失效的问题。
 *
 * 规则：
 * 1. 精确匹配 CORE_PRODUCT_TERMS 中的词 → 直接映射
 * 2. 宽泛类目（如"餐具水具"）→ 通过关键词二次归一
 * 3. 配件后缀检测 → productRole = accessory
 */
public final class CategoryNormalizer {

    private CategoryNormalizer() {}

    // 品类词 → categoryCode 的精确映射
    private static final Map<String, String> CATEGORY_MAP = Map.ofEntries(
            // 杯子类
            Map.entry("杯子", "cup"), Map.entry("水杯", "cup"), Map.entry("保温杯", "cup"),
            Map.entry("玻璃杯", "cup"), Map.entry("马克杯", "cup"), Map.entry("茶杯", "cup"),
            Map.entry("咖啡杯", "cup"), Map.entry("杯", "cup"),
            // 手机类
            Map.entry("手机", "phone"), Map.entry("电话", "phone"),
            // 手机品牌/型号别名（标题不含"手机"时也能归一化）
            Map.entry("iphone", "phone"), Map.entry("苹果手机", "phone"),
            Map.entry("小米手机", "phone"), Map.entry("华为手机", "phone"),
            Map.entry("oppo", "phone"), Map.entry("vivo", "phone"),
            Map.entry("荣耀手机", "phone"), Map.entry("三星手机", "phone"),
            Map.entry("galaxy", "phone"), Map.entry("redmi", "phone"),
            Map.entry("realme", "phone"), Map.entry("一加", "phone"),
            Map.entry("iqoo", "phone"),
            // 耳机类
            Map.entry("耳机", "headphone"), Map.entry("蓝牙耳机", "headphone"),
            Map.entry("降噪耳机", "headphone"), Map.entry("头戴式耳机", "headphone"),
            // 耳机品牌别名
            Map.entry("airpods", "headphone"), Map.entry("buds", "headphone"),
            // 手表类
            Map.entry("手表", "watch"), Map.entry("智能手表", "watch"),
            Map.entry("手环", "watch"), Map.entry("智能手环", "watch"),
            // 电脑类
            Map.entry("笔记本", "laptop"), Map.entry("电脑", "laptop"),
            Map.entry("平板", "tablet"),
            // 鞋类
            Map.entry("运动鞋", "shoe"), Map.entry("跑鞋", "shoe"),
            Map.entry("篮球鞋", "shoe"), Map.entry("板鞋", "shoe"),
            Map.entry("皮鞋", "shoe"), Map.entry("拖鞋", "shoe"),
            Map.entry("凉鞋", "shoe"), Map.entry("高跟鞋", "shoe"),
            Map.entry("鞋", "shoe"),
            // 箱包类
            Map.entry("双肩包", "bag"), Map.entry("手提包", "bag"),
            Map.entry("行李箱", "luggage"), Map.entry("拉杆箱", "luggage"),
            // 剃须刀
            Map.entry("剃须刀", "shaver"), Map.entry("电动剃须刀", "shaver"),
            Map.entry("刮胡刀", "shaver"),
            // 吹风机
            Map.entry("吹风机", "hairdryer"),
            // 电动牙刷
            Map.entry("电动牙刷", "toothbrush"), Map.entry("牙刷", "toothbrush"),
            // 相机
            Map.entry("相机", "camera"), Map.entry("摄像机", "camera"),
            // 键盘鼠标
            Map.entry("键盘", "keyboard"), Map.entry("鼠标", "mouse"),
            // 其他数码
            Map.entry("充电器", "charger"), Map.entry("充电宝", "powerbank"),
            Map.entry("移动电源", "powerbank"), Map.entry("数据线", "cable"),
            Map.entry("路由器", "router"), Map.entry("投影仪", "projector"),
            Map.entry("打印机", "printer"), Map.entry("显示器", "monitor"),
            Map.entry("音箱", "speaker"), Map.entry("无人机", "drone"),
            // 家电
            Map.entry("空调", "aircon"), Map.entry("冰箱", "fridge"),
            Map.entry("洗衣机", "washer"), Map.entry("电视", "tv"),
            Map.entry("电饭煲", "ricecooker"), Map.entry("微波炉", "microwave"),
            Map.entry("烤箱", "oven"), Map.entry("空气炸锅", "airfryer"),
            Map.entry("吸尘器", "vacuum"), Map.entry("扫地机器人", "robotvacuum"),
            Map.entry("咖啡机", "coffeemaker"),
            // 美妆
            Map.entry("口红", "lipstick"), Map.entry("粉底液", "foundation"),
            Map.entry("面膜", "mask"), Map.entry("防晒霜", "sunscreen"),
            Map.entry("洗面奶", "cleanser"), Map.entry("精华液", "serum"),
            // 饰品
            Map.entry("项链", "necklace"), Map.entry("戒指", "ring"),
            Map.entry("耳环", "earring"), Map.entry("墨镜", "sunglasses"),
            // 母婴
            Map.entry("奶瓶", "babybottle"), Map.entry("纸尿裤", "diaper"),
            Map.entry("婴儿车", "stroller"),
            // 运动
            Map.entry("瑜伽垫", "yogamat"), Map.entry("跑步机", "treadmill"),
            Map.entry("哑铃", "dumbbell"), Map.entry("自行车", "bicycle"),
            // 食品
            Map.entry("咖啡", "coffee"), Map.entry("茶叶", "tea"),
            Map.entry("牛奶", "milk"), Map.entry("猫粮", "catfood"),
            Map.entry("狗粮", "dogfood"),
            // 工具
            Map.entry("电钻", "drill"),
            // 乐器
            Map.entry("吉他", "guitar"), Map.entry("钢琴", "piano"),
            // 家具
            Map.entry("沙发", "sofa"), Map.entry("床", "bed"),
            Map.entry("桌子", "table"), Map.entry("椅子", "chair"),
            Map.entry("书架", "bookshelf"),
            // 日用
            Map.entry("雨伞", "umbrella"), Map.entry("伞", "umbrella"),
            Map.entry("毛巾", "towel"), Map.entry("枕头", "pillow"),
            Map.entry("被子", "quilt")
    );

    // 宽泛类目 → 尝试从关键词中提取精确 categoryCode
    private static final Map<String, String> BROAD_CATEGORY_HINTS = Map.ofEntries(
            Map.entry("餐具水具", "cup"), Map.entry("水具", "cup"), Map.entry("餐具", "cup"),
            Map.entry("数码3C", ""), Map.entry("数码", ""), Map.entry("电子产品", ""),
            Map.entry("家电", ""), Map.entry("家居用品", ""), Map.entry("日用品", ""),
            Map.entry("美妆", ""), Map.entry("护肤", ""), Map.entry("服饰", ""),
            Map.entry("鞋靴", "shoe"), Map.entry("箱包", "bag"),
            Map.entry("母婴", ""), Map.entry("食品", ""), Map.entry("运动户外", "")
    );

    /**
     * 从品类和关键词中归一化出 mainCategoryCode。
     * 优先级：精确品类匹配 > 宽泛品类+关键词二次归一 > 关键词匹配
     *
     * @param category VLM 返回的品类（可能宽泛如"餐具水具"）
     * @param keywords 搜索关键词列表
     * @return 归一化后的 categoryCode，如 "cup"、"phone"；无法归一则返回 ""
     */
    public static String normalize(String category, java.util.List<String> keywords) {
        String cat = SearchTextUtils.useful(category).toLowerCase(Locale.ROOT);

        // 1. 精确匹配品类
        if (!cat.isBlank()) {
            String exact = CATEGORY_MAP.get(cat);
            if (exact != null) return exact;

            // 遍历 CATEGORY_MAP 做包含匹配（如"保温水杯"包含"水杯"）
            for (Map.Entry<String, String> entry : CATEGORY_MAP.entrySet()) {
                if (cat.contains(entry.getKey())) {
                    return entry.getValue();
                }
            }
        }

        // 2. 宽泛品类 → 从关键词中提取
        if (!cat.isBlank()) {
            String hint = BROAD_CATEGORY_HINTS.get(cat);
            if (hint != null && !hint.isBlank()) return hint;
            // 宽泛品类但无直接映射，继续用关键词
        }

        // 3. 从关键词中提取
        if (keywords != null) {
            for (String kw : keywords) {
                String useful = SearchTextUtils.useful(kw).toLowerCase(Locale.ROOT);
                if (useful.isBlank()) continue;
                String code = CATEGORY_MAP.get(useful);
                if (code != null) return code;
                for (Map.Entry<String, String> entry : CATEGORY_MAP.entrySet()) {
                    if (useful.contains(entry.getKey())) {
                        return entry.getValue();
                    }
                }
            }
        }

        return "";
    }

    /**
     * 从商品标题中推断 productRole。
     *
     * @param title           商品标题
     * @param mainCategoryCode 主品类 code（如 "cup"）
     * @return "main" | "accessory" | "consumable" | "unknown"
     */
    public static String inferRole(String title, String mainCategoryCode) {
        if (title == null || title.isBlank() || mainCategoryCode.isBlank()) {
            return "unknown";
        }
        String lower = title.toLowerCase(Locale.ROOT);

        // 如果标题匹配配件排除列表 → accessory
        for (Map.Entry<String, java.util.List<String>> entry : SearchTextUtils.getSubcategoryExclusions().entrySet()) {
            if (CATEGORY_MAP.containsKey(entry.getKey()) && CATEGORY_MAP.get(entry.getKey()).equals(mainCategoryCode)) {
                for (String excluded : entry.getValue()) {
                    if (lower.contains(excluded.toLowerCase(Locale.ROOT))) {
                        return "accessory";
                    }
                }
            }
        }

        // 通用配件后缀检测
        String suffixChars = "套壳膜盖塞垫架座扣夹绳链环刷布罩袋盒";
        for (Map.Entry<String, String> entry : CATEGORY_MAP.entrySet()) {
            if (!entry.getValue().equals(mainCategoryCode)) continue;
            String coreLower = entry.getKey().toLowerCase(Locale.ROOT);
            if (lower.startsWith(coreLower) && lower.length() > coreLower.length()) {
                if (suffixChars.indexOf(lower.charAt(coreLower.length())) >= 0) {
                    return "accessory";
                }
            }
        }

        // 消耗品检测
        String[] consumablePatterns = {"膏", "液", "油", "剂", "粉", "片", "纸", "贴", "膜"};
        for (String pattern : consumablePatterns) {
            if (lower.endsWith(pattern) && lower.length() <= 6) {
                return "consumable";
            }
        }

        return "main";
    }

    /**
     * 判断搜索意图的 targetRole。
     * 如果用户搜索的是配件（如"手机壳"），返回 "accessory"；否则返回 "main"。
     */
    public static String intentRole(String category, java.util.List<String> keywords) {
        // 检查关键词中是否含配件术语
        if (keywords != null) {
            for (String kw : keywords) {
                if (SearchTextUtils.isAccessoryTerm(kw)) return "accessory";
            }
        }
        if (SearchTextUtils.isAccessoryTerm(SearchTextUtils.useful(category))) {
            return "accessory";
        }
        return "main";
    }

    /**
     * 从任意文本中扫描品类关键词，返回匹配到的 categoryCode。
     * 用于 NLP 新搜索意图检测等场景。
     *
     * @param text 用户输入文本
     * @return 匹配到的 categoryCode，未匹配返回 ""
     */
    public static String normalizeFromText(String text) {
        if (text == null || text.isBlank()) return "";
        String lower = text.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : CATEGORY_MAP.entrySet()) {
            if (lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "";
    }

    /**
     * 从 categoryCode 反查一个中文显示名（用于 UI 展示）。
     * 返回该 code 对应的第一个中文 key；未找到返回 code 本身。
     */
    public static String displayName(String categoryCode) {
        if (categoryCode == null || categoryCode.isBlank()) return "新商品";
        for (Map.Entry<String, String> entry : CATEGORY_MAP.entrySet()) {
            if (entry.getValue().equals(categoryCode) && !isPinyinOrEnglish(entry.getKey())) {
                return entry.getKey();
            }
        }
        return categoryCode;
    }

    private static boolean isPinyinOrEnglish(String key) {
        for (char c : key.toCharArray()) {
            if (c >= 0x4E00 && c <= 0x9FFF) return false; // CJK char found
        }
        return true;
    }
}
