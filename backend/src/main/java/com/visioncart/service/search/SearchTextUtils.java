package com.visioncart.service.search;

import com.visioncart.api.dto.SearchFilter;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public final class SearchTextUtils {

    public static final String ATTR_BRAND = "品牌";
    public static final String ATTR_COLOR = "颜色";
    public static final String ATTR_STYLE = "款式";
    public static final String ATTR_CATEGORY = "类目";
    public static final String ATTR_KEYWORD = "关键词";
    public static final String ATTR_KEYWORDS = "__keywords";
    public static final String ATTR_CATEGORY_CHAIN = "__category_chain";
    public static final String ATTR_BRAND_RELIABLE = "__brand_reliable";
    public static final String ATTR_STRICT_INTENT = "__strict_intent";

    private SearchTextUtils() {}

    public static String useful(String value) {
        String trimmed = StringUtils.defaultString(value).trim();
        return trimmed.isBlank()
                || "未知".equals(trimmed)
                || "未识别".equals(trimmed)
                || "通用".equals(trimmed)
                || "常规".equals(trimmed)
                || "常规款".equals(trimmed)
                || "常规款式".equals(trimmed)
                || "普通款".equals(trimmed)
                || "标准款".equals(trimmed)
                || "基础款".equals(trimmed)
                || "无".equals(trimmed)
                || "无品牌".equals(trimmed)
                || "null".equalsIgnoreCase(trimmed)
                || "unknown".equalsIgnoreCase(trimmed)
                ? ""
                : trimmed;
    }

    public static String searchKeyword(Map<String, String> attributes, SearchFilter filter, String fallback) {
        String explicit = positiveKeyword(filter == null ? null : filter.keyword());
        if (StringUtils.isNotBlank(explicit)) {
            return explicit;
        }

        Map<String, String> source = attributes == null ? Map.of() : attributes;
        String recognizedKeyword = useful(source.get(ATTR_KEYWORD));
        if (StringUtils.isNotBlank(recognizedKeyword)) {
            return recognizedKeyword;
        }

        String joined = Arrays.stream(new String[] {
                        source.get(ATTR_BRAND),
                        source.get(ATTR_COLOR),
                        source.get(ATTR_STYLE),
                        source.get(ATTR_CATEGORY)
                })
                .map(SearchTextUtils::useful)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.joining(" "));
        return StringUtils.defaultIfBlank(joined, fallback);
    }

    public static String positiveKeyword(String keyword) {
        String raw = StringUtils.defaultString(keyword).trim();
        if (raw.startsWith("!") || raw.startsWith("[")) {
            return "";
        }
        return splitSearchTerms(keyword).stream()
                .filter(term -> !term.startsWith("!"))
                .findFirst()
                .orElse("");
    }

    public static List<String> negativeTerms(String keyword) {
        String raw = StringUtils.defaultString(keyword).trim();
        if (raw.startsWith("!")) {
            return splitSearchTerms(raw.substring(1)).stream()
                    .map(SearchTextUtils::useful)
                    .filter(StringUtils::isNotBlank)
                    .distinct()
                    .toList();
        }
        return splitSearchTerms(keyword).stream()
                .filter(term -> term.startsWith("!"))
                .map(term -> useful(term.substring(1)))
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
    }

    public static long parseHumanCount(String value) {
        String text = StringUtils.defaultString(value).trim().replace(",", "");
        if (text.isBlank()) {
            return 0;
        }

        double multiplier = 1;
        if (text.contains("万")) {
            multiplier = 10_000;
        } else if (text.contains("千")) {
            multiplier = 1_000;
        }

        String number = text.replaceAll("[^0-9.]", "");
        if (number.isBlank()) {
            return 0;
        }
        try {
            return Math.round(Double.parseDouble(number) * multiplier);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static long maxHumanCount(String... values) {
        long max = 0;
        for (String value : values) {
            max = Math.max(max, parseHumanCount(value));
        }
        return max;
    }

    public static String normalizeUrl(String url) {
        String trimmed = StringUtils.defaultString(url).trim();
        if (trimmed.isBlank()) {
            return "";
        }
        if (trimmed.startsWith("//")) {
            return "https:" + trimmed;
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        return "https://" + trimmed;
    }

    public static boolean relevantToCoreProduct(String title, Map<String, String> attributes) {
        String normalizedTitle = StringUtils.defaultString(title).toLowerCase(Locale.ROOT);
        String category = useful(attributes == null ? null : attributes.get(ATTR_CATEGORY));
        if (StringUtils.isBlank(category)) {
            return true;
        }

        String normalizedCategory = category.toLowerCase(Locale.ROOT);
        if (normalizedTitle.contains(normalizedCategory)) {
            return true;
        }

        // 从类目中找到核心产品词（如"无线鼠标"中的"鼠标"），检查标题是否包含
        String clean = category.replaceAll("[\\s/]+", "");
        for (String term : CORE_PRODUCT_TERMS) {
            if (clean.contains(term) && term.length() >= 2) {
                if (normalizedTitle.contains(term.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String coreProductToken(Map<String, String> attributes) {
        if (attributes == null) {
            return "";
        }
        String category = useful(attributes.get(ATTR_CATEGORY));
        if (StringUtils.isNotBlank(category)) {
            return coreProductToken(category);
        }
        String keyword = useful(attributes.get(ATTR_KEYWORD));
        return coreProductToken(keyword);
    }

    public static String coreProductToken(String value) {
        String clean = useful(value).replaceAll("[\\s/]+", "");
        if (clean.isBlank()) {
            return "";
        }
        // 找到 clean 中包含的最长 CORE_PRODUCT_TERM，返回该词本身（不含前缀修饰词）
        String bestMatch = "";
        for (String term : CORE_PRODUCT_TERMS) {
            if (clean.contains(term) && term.length() > bestMatch.length()) {
                bestMatch = term;
            }
        }
        if (!bestMatch.isEmpty()) {
            return bestMatch;
        }
        List<String> tokens = categoryTokens(clean.toLowerCase(Locale.ROOT));
        if (tokens.isEmpty()) {
            return clean;
        }
        return tokens.get(tokens.size() - 1);
    }

    // 通用类型修饰词（不限于某个品类），用于区分产品子类型
    private static final List<String> TYPE_MODIFIERS = List.of(
            "电动", "无线", "有线", "自动", "手动", "智能", "便携", "迷你", "大功率", "小型", "大型",
            "折叠", "防水", "静音", "变频", "定频", "双频", "三合一", "二合一",
            "蒸汽", "超声波", "磁吸", "挂壁", "台式", "手持", "立式", "卧式",
            "充电", "插电", "太阳能", "蓝牙", "wifi",
            "单头", "双头", "三头", "大号", "小号", "中号"
    );

    /**
     * 从品类字符串中提取类型修饰词（如"电动"从"电动剃须刀"中）。
     * 修饰词是决定产品子类型的关键词，如电动/手动、无线/有线等。
     */
    public static String extractModifier(String category) {
        String clean = useful(category).replaceAll("[\\s/]+", "");
        if (clean.isBlank()) {
            return "";
        }
        for (String mod : TYPE_MODIFIERS) {
            if (clean.contains(mod)) {
                return mod;
            }
        }
        return "";
    }

    public static String inferBrand(String title, String shopName) {
        return BrandMatcher.inferBrand(title, shopName);
    }

    public static List<String> splitSearchTerms(String value) {
        String useful = useful(value);
        if (useful.isBlank()) {
            return List.of();
        }
        return Arrays.stream(useful.split("[,，;；|\\n\\r]+"))
                .map(SearchTextUtils::useful)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
    }

    public static boolean containsNormalized(String text, String token) {
        String normalizedToken = normalizeForMatch(token);
        return !normalizedToken.isBlank()
                && normalizeForMatch(text).contains(normalizedToken);
    }

    public static String normalizeForMatch(String value) {
        return StringUtils.defaultString(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHan}a-z0-9]+", "");
    }

    public static String salesLabel(long sales, String source) {
        if (sales <= 0) {
            return "销量未知";
        }
        String count = formatHumanCount(sales);
        if ("annual".equals(source)) {
            return "年销 " + count;
        }
        if ("promotion".equals(source)) {
            return "推广销量 " + count;
        }
        return "30天销量 " + count;
    }

    private static String formatHumanCount(long value) {
        if (value >= 10_000) {
            double tenThousands = value / 10_000.0;
            return String.format(Locale.ROOT, tenThousands >= 10 ? "%.0f万+" : "%.1f万+", tenThousands);
        }
        return value + "+";
    }

    private static List<String> categoryTokens(String category) {
        String clean = category.replaceAll("[\\s/]+", "");
        List<String> tokens = new ArrayList<>();
        if (clean.length() <= 4 && clean.length() >= 2) {
            tokens.add(clean.substring(0, 2));
            String tail = clean.substring(clean.length() - 2);
            if (!tokens.contains(tail)) {
                tokens.add(tail);
            }
            return tokens.stream()
                    .filter(token -> !GENERIC_CATEGORY_TOKENS.contains(token))
                    .toList();
        }
        for (int i = 0; i < clean.length() - 1; i++) {
            String token = clean.substring(i, i + 2);
            if (!GENERIC_CATEGORY_TOKENS.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static final List<String> GENERIC_CATEGORY_TOKENS = List.of(
            "商品", "产品", "配件", "用品", "通用", "其他"
    );

    private static final List<String> CORE_PRODUCT_TERMS = List.of(
            // === 鞋类 ===
            "运动鞋", "篮球鞋", "跑鞋", "板鞋", "休闲鞋", "训练鞋", "足球鞋", "网球鞋", "羽毛球鞋",
            "登山鞋", "徒步鞋", "雪地靴", "马丁靴", "工装靴", "高跟鞋", "平底鞋", "单鞋",
            "拖鞋", "凉鞋", "皮鞋", "帆布鞋", "老爹鞋", "豆豆鞋", "豆鞋", "穆勒鞋",
            // === 数码3C ===
            "手机", "电脑", "笔记本", "平板", "显示器", "电视",
            "鼠标", "键盘", "耳机", "音箱", "相机", "摄像机", "无人机",
            "路由器", "交换机", "网卡", "充电器", "充电宝", "移动电源", "数据线", "充电线",
            "智能手环", "智能手表", "投影仪", "打印机", "扫描仪", "移动硬盘", "U盘",
            "显卡", "内存条", "硬盘", "主板", "CPU", "电源", "机箱", "散热器",
            "游戏机", "游戏手柄", "游戏键盘", "游戏鼠标",
            // === 大家电 ===
            "空调", "冰箱", "洗衣机", "烘干机", "电视机", "洗碗机", "热水器", "壁挂炉",
            // === 厨房电器 ===
            "电饭煲", "微波炉", "烤箱", "空气炸锅", "电磁炉", "电陶炉", "电烤炉",
            "咖啡机", "榨汁机", "豆浆机", "料理机", "破壁机", "绞肉机",
            "热水壶", "电水壶", "养生壶", "煮蛋器", "面包机", "酸奶机", "电炖锅",
            "油烟机", "燃气灶", "消毒柜", "洗碗机",
            // === 清洁/环境电器 ===
            "扫地机器人", "吸尘器", "洗地机", "蒸汽拖把", "除螨仪",
            "净水器", "饮水机", "加湿器", "空气净化器", "除湿机", "新风机",
            // === 生活电器 ===
            "电风扇", "空调扇", "取暖器", "电暖器", "电热毯",
            "挂烫机", "电熨斗", "缝纫机",
            "按摩椅", "按摩器", "足浴盆", "血压计", "体温计", "血氧仪",
            "电动床", "智能马桶",
            // === 个护电器 ===
            "剃须刀", "电动剃须刀", "吹风机", "电动牙刷", "冲牙器", "水牙线",
            "卷发棒", "直发器", "直发梳", "卷发器",
            "洁面仪", "美容仪", "脱毛仪", "理发器", "电推剪", "鼻毛修剪器",
            "蒸脸器", "导入仪", "射频仪",
            // === 照明 ===
            "台灯", "落地灯", "吊灯", "吸顶灯", "射灯", "筒灯", "灯带", "壁灯", "应急灯",
            // === 服饰 ===
            "连衣裙", "T恤", "衬衫", "polo衫",
            "外套", "夹克", "风衣", "大衣", "羽绒服", "棉服", "皮衣",
            "西装", "西裤", "西服",
            "卫衣", "毛衣", "针织衫", "开衫",
            "牛仔裤", "休闲裤", "运动裤", "工装裤", "阔腿裤", "打底裤", "紧身裤",
            "短裤", "七分裤", "九分裤",
            "半身裙", "短裙", "长裙", "百褶裙", "A字裙",
            "睡衣", "家居服", "内衣", "内裤", "文胸", "背心", "袜子",
            "泳衣", "泳裤", "比基尼",
            "校服", "工作服", "防护服",
            // === 运动户外 ===
            "冲锋衣", "速干衣", "瑜伽裤", "瑜伽服", "健身服", "骑行服",
            "登山包", "帐篷", "睡袋", "登山杖", "折叠椅",
            "自行车", "电动车", "滑板车", "平衡车", "独轮车",
            "篮球", "足球", "排球", "网球", "羽毛球", "乒乓球",
            "哑铃", "杠铃", "跑步机", "椭圆机", "划船机", "动感单车", "瑜伽垫", "跳绳",
            "望远镜", "指南针", "头灯", "手电筒",
            // === 箱包 ===
            "双肩包", "手提包", "斜挎包", "单肩包", "钱包", "卡包", "腰包", "胸包",
            "行李箱", "拉杆箱", "旅行包", "洗漱包", "化妆包",
            "电脑包", "公文包", "邮差包",
            // === 美妆护肤 ===
            "口红", "唇釉", "唇膏", "唇彩",
            "粉底液", "粉底霜", "粉底膏", "气垫", "散粉", "蜜粉", "粉饼",
            "眼影", "眼线笔", "睫毛膏", "眉笔", "眉粉",
            "腮红", "高光", "修容", "遮瑕",
            "面膜", "眼膜", "唇膜",
            "防晒霜", "防晒喷雾", "隔离霜",
            "精华液", "精华霜", "肌底液",
            "面霜", "乳液", "爽肤水", "化妆水", "柔肤水",
            "洗面奶", "卸妆水", "卸妆油", "卸妆膏",
            "身体乳", "护手霜", "磨砂膏",
            "香水", "香氛", "古龙水",
            "美甲", "指甲油", "甲油胶",
            // === 饰品 ===
            "项链", "手链", "手镯", "戒指", "耳环", "耳钉", "胸针", "发饰",
            "墨镜", "太阳镜", "近视镜", "老花镜", "眼镜框",
            "帽子", "棒球帽", "渔夫帽", "遮阳帽",
            "围巾", "丝巾", "领带", "领结", "皮带", "腰带",
            // === 母婴 ===
            "奶瓶", "奶嘴", "吸奶器", "温奶器",
            "纸尿裤", "拉拉裤", "湿巾",
            "婴儿车", "安全座椅", "婴儿床", "摇篮", "背带", "腰凳",
            "辅食机", "辅食剪", "研磨碗",
            "积木", "拼图", "遥控车", "毛绒玩具", "益智玩具",
            // === 家纺 ===
            "枕头", "被子", "被套", "床单", "床笠", "床罩",
            "床垫", "凉席", "蚊帐",
            "窗帘", "浴巾", "毛巾", "地垫", "地毯",
            "抱枕", "靠垫", "坐垫",
            // === 家具 ===
            "沙发", "茶几", "电视柜", "餐桌", "餐椅", "书桌", "电脑桌", "办公椅",
            "书架", "衣柜", "鞋柜", "床头柜", "储物柜", "置物架",
            "折叠桌", "折叠椅", "懒人沙发",
            // === 家居日用 ===
            "收纳盒", "收纳箱", "置物架", "衣架", "裤架",
            "垃圾桶", "垃圾袋", "保鲜膜", "保鲜袋", "密封罐",
            "拖把", "扫把", "抹布", "刷子",
            "剪刀", "美工刀", "螺丝刀", "扳手",
            "花瓶", "相框", "摆件", "挂钟",
            // === 食品 ===
            "牛奶", "酸奶", "奶粉", "豆奶",
            "咖啡", "茶叶", "奶茶",
            "巧克力", "糖果", "饼干", "蛋糕", "面包", "点心",
            "坚果", "瓜子", "花生", "核桃", "杏仁", "腰果",
            "方便面", "自热饭", "自热火锅", "速冻水饺", "速冻汤圆",
            "火腿肠", "肉松", "牛肉干", "猪肉脯", "鸡爪",
            "海苔", "果冻", "膨化食品", "薯片",
            // === 饮料 ===
            "矿泉水", "纯净水", "苏打水", "气泡水",
            "可乐", "雪碧", "果汁", "功能饮料", "运动饮料",
            "啤酒", "红酒", "白酒", "洋酒",
            // === 水果生鲜 ===
            "苹果", "香蕉", "橙子", "葡萄", "草莓", "蓝莓", "车厘子", "榴莲",
            "牛肉", "猪肉", "鸡肉", "羊肉", "虾", "鱼", "螃蟹",
            "鸡蛋", "鸭蛋",
            // === 宠物 ===
            "猫粮", "狗粮", "猫砂", "宠物零食",
            "猫窝", "狗窝", "猫爬架", "宠物玩具",
            "牵引绳", "宠物背包", "宠物推车",
            // === 办公文具 ===
            "笔记本", "记事本", "便签", "文件夹", "文件袋",
            "钢笔", "中性笔", "铅笔", "圆珠笔", "马克笔",
            "打印纸", "复印纸", "相纸",
            "订书机", "打孔器", "计算器",
            // === 汽车用品 ===
            "行车记录仪", "车载充电器", "车载手机支架", "车载香薰",
            "汽车坐垫", "汽车脚垫", "方向盘套", "遮阳帘",
            "车载冰箱", "车载吸尘器", "车载充气泵",
            // === 工具 ===
            "电钻", "电锤", "电锯", "角磨机", "热风枪",
            "万用表", "测电笔", "水平仪",
            "工具箱", "工具包",
            // === 乐器 ===
            "吉他", "尤克里里", "钢琴", "电子琴", "小提琴",
            "口琴", "竖笛", "架子鼓",
            // === 餐具水具 ===
            "水杯", "杯子", "保温杯", "玻璃杯", "马克杯", "茶杯", "咖啡杯",
            "水壶", "保温壶", "茶壶", "花瓶", "碗", "盘子", "筷子", "勺子", "叉子",
            // === 日用品 ===
            "雨伞", "雨衣", "纸巾", "湿巾", "垃圾袋", "保鲜袋",
            // === 通用短词 ===
            "鞋", "包", "衣", "裤", "裙", "杯", "刀", "锅", "碗", "瓶", "灯", "镜"
    );

    public static List<String> getCoreProductTerms() {
        return CORE_PRODUCT_TERMS;
    }

    public static Map<String, List<String>> getSubcategoryExclusions() {
        return SUBCATEGORY_EXCLUSIONS;
    }

    // 同类产品分组：同一组内的词可以互相替代
    private static final List<List<String>> PRODUCT_GROUPS = List.of(
            // 餐具水具
            List.of("水杯", "杯子", "保温杯", "玻璃杯", "马克杯", "茶杯", "咖啡杯"),
            List.of("水壶", "保温壶", "茶壶", "电热水壶"),
            // 鞋类
            List.of("运动鞋", "篮球鞋", "跑鞋", "板鞋", "休闲鞋", "训练鞋", "足球鞋", "网球鞋", "羽毛球鞋", "登山鞋", "徒步鞋"),
            List.of("拖鞋", "凉鞋", "皮鞋", "帆布鞋", "老爹鞋"),
            List.of("雪地靴", "马丁靴", "工装靴"),
            List.of("高跟鞋", "平底鞋", "单鞋"),
            // 数码3C
            List.of("手机", "电脑", "笔记本", "平板", "iPad"),
            List.of("鼠标", "键盘", "游戏鼠标", "游戏键盘"),
            List.of("耳机", "音箱", "蓝牙耳机", "降噪耳机"),
            List.of("充电器", "充电头", "快充头", "氮化镓充电器"),
            List.of("移动电源", "充电宝", "应急电源", "户外电源", "便携充电器"),
            List.of("数据线", "充电线", "Type-C线", "Lightning线"),
            List.of("手机壳", "手机套", "保护壳", "保护套"),
            List.of("手表", "智能手表", "手环", "智能手环"),
            // 个护电器
            List.of("剃须刀", "电动剃须刀", "刮胡刀"),
            List.of("吹风机", "卷发棒", "直发器", "直发梳", "卷发器"),
            List.of("电动牙刷", "冲牙器", "水牙线"),
            List.of("美容仪", "洁面仪", "导入仪"),
            // 服饰
            List.of("连衣裙", "半身裙", "短裙", "长裙", "百褶裙", "A字裙"),
            List.of("T恤", "衬衫", "polo衫", "短袖"),
            List.of("外套", "夹克", "风衣", "大衣", "羽绒服", "棉服"),
            List.of("卫衣", "毛衣", "针织衫", "开衫"),
            List.of("牛仔裤", "休闲裤", "运动裤", "工装裤", "阔腿裤"),
            List.of("短裤", "七分裤", "九分裤"),
            List.of("内衣", "文胸", "内裤", "背心"),
            // 箱包
            List.of("双肩包", "手提包", "斜挎包", "单肩包", "电脑包", "公文包", "书包"),
            List.of("行李箱", "拉杆箱", "旅行箱", "旅行包"),
            List.of("钱包", "卡包", "腰包", "胸包"),
            // 家具
            List.of("沙发", "茶几", "电视柜", "餐桌", "餐椅"),
            List.of("书桌", "电脑桌", "办公桌", "办公椅"),
            List.of("床", "床垫", "床头柜", "衣柜"),
            // 照明
            List.of("台灯", "落地灯", "吊灯", "吸顶灯", "射灯"),
            // 厨房电器
            List.of("电饭煲", "电饭锅", "压力锅"),
            List.of("微波炉", "烤箱", "空气炸锅"),
            List.of("豆浆机", "榨汁机", "破壁机", "料理机"),
            // 清洁
            List.of("吸尘器", "扫地机器人", "洗地机"),
            List.of("洗衣机", "烘干机", "洗烘一体机"),
            // 食品
            List.of("牛奶", "酸奶", "奶粉"),
            List.of("咖啡", "茶叶", "奶茶"),
            // 母婴
            List.of("奶瓶", "奶嘴", "吸奶器"),
            List.of("纸尿裤", "拉拉裤", "尿不湿"),
            List.of("婴儿车", "推车", "儿童推车")
    );

    /**
     * 返回与给定核心产品同组的其他产品词。
     * 如 siblingProductTerms("保温杯") → ["水杯","杯子","玻璃杯","马克杯","茶杯","咖啡杯"]
     */
    public static List<String> siblingProductTerms(String coreProduct) {
        if (coreProduct == null || coreProduct.isBlank()) return List.of();
        String lower = coreProduct.toLowerCase(java.util.Locale.ROOT);
        for (List<String> group : PRODUCT_GROUPS) {
            if (group.stream().anyMatch(term -> term.equals(lower) || lower.contains(term))) {
                return group.stream()
                        .filter(term -> !term.equals(lower) && !lower.contains(term))
                        .toList();
            }
        }
        return List.of();
    }

    // 通用配件关键词 — 任何品类标题中出现这些词且品类本身不是配件时应被过滤
    private static final List<String> COMMON_ACCESSORY_TERMS = List.of(
            "充电器", "电池", "充电线", "充电底座", "充电座", "数据线",
            "保护壳", "保护套", "手机壳", "手机膜", "贴膜",
            "支架", "收纳袋", "收纳盒", "收纳包",
            "替换头", "替换装", "备用", "配件", "耗材",
            "清洁剂", "清洁液", "清洗剂",
            "防尘罩", "防尘套", "防尘袋", "收纳架"
    );

    // 配件后缀单字 — 标题以"核心产品词+以下单字"开头时判定为配件
    // 如"杯套"(杯+套)、"手机壳"(手机+壳)、"耳机膜"(耳机+膜)
    static final String ACCESSORY_SUFFIX_CHARS = "套壳膜盖塞垫架座扣夹绳链环刷布罩袋盒";

    private static final Map<String, List<String>> SUBCATEGORY_EXCLUSIONS = new HashMap<>() {{
        // === 餐具水具 ===
        put("杯子", List.of("杯套", "杯垫", "杯盖", "杯刷", "杯架", "杯袋", "杯绳", "挂绳", "吸管", "搅拌棒", "隔热套", "隔热垫", "保护套"));
        put("水杯", List.of("杯套", "杯垫", "杯盖", "杯刷", "杯架", "杯袋", "杯绳", "挂绳", "吸管", "搅拌棒", "隔热套", "隔热垫", "保护套", "杯塞"));
        put("保温杯", List.of("杯套", "杯垫", "杯盖", "杯刷", "杯架", "杯袋", "杯绳", "挂绳", "吸管", "隔热套", "隔热垫", "保护套", "密封圈", "杯塞"));
        put("玻璃杯", List.of("杯套", "杯垫", "杯盖", "杯刷", "杯架", "杯袋", "杯绳", "挂绳", "吸管", "搅拌棒", "隔热套", "隔热垫", "保护套"));
        put("马克杯", List.of("杯套", "杯垫", "杯盖", "杯刷", "杯架", "杯袋", "杯绳", "挂绳", "吸管", "搅拌棒", "隔热套", "隔热垫"));
        put("茶杯", List.of("杯套", "杯垫", "杯盖", "杯刷", "杯架", "杯袋", "杯绳", "挂绳", "隔热垫"));
        put("咖啡杯", List.of("杯套", "杯垫", "杯盖", "杯刷", "杯架", "杯袋", "杯绳", "挂绳", "吸管", "搅拌棒", "隔热套", "隔热垫"));
        // === 厨具 ===
        put("刀", List.of("刀套", "刀架", "刀座", "磨刀石", "磨刀棒", "刀袋"));
        put("菜刀", List.of("刀套", "刀架", "刀座", "磨刀石", "磨刀棒", "刀袋"));
        put("锅", List.of("锅盖", "锅架", "锅垫", "锅铲", "锅刷"));
        put("炒锅", List.of("锅盖", "锅架", "锅垫", "锅铲", "锅刷", "蒸笼"));
        put("碗", List.of("碗架", "碗垫", "碗盖"));
        put("盘", List.of("盘架", "盘垫", "盘盖"));
        put("筷子", List.of("筷笼", "筷架", "筷盒"));
        // === 容器 ===
        put("瓶", List.of("瓶盖", "瓶塞", "瓶套", "瓶刷", "瓶架", "瓶垫"));
        put("罐", List.of("罐盖", "罐塞", "罐套"));
        put("盒", List.of("盒盖", "盒套"));
        put("袋", List.of("袋盖", "袋套"));
        // === 雨具 ===
        put("伞", List.of("伞套", "伞架", "伞座", "伞袋", "伞扣"));
        put("雨伞", List.of("伞套", "伞架", "伞座", "伞袋", "伞扣"));
        // === 眼镜 ===
        put("眼镜", List.of("眼镜盒", "眼镜布", "眼镜链", "眼镜袋", "鼻托", "镜布"));
        put("墨镜", List.of("眼镜盒", "眼镜布", "眼镜链", "眼镜袋", "鼻托"));
        // === 灯具 ===
        put("灯", List.of("灯罩", "灯座", "灯架", "灯泡"));
        put("台灯", List.of("灯罩", "灯座", "灯架", "灯泡"));
        // === 乐器 ===
        put("吉他", List.of("琴弦", "琴包", "琴架", "拨片", "变调夹", "调音器", "背带", "效果器"));
        put("琴", List.of("琴弦", "琴包", "琴架", "琴凳", "琴罩", "琴谱架"));
        // === 文具 ===
        put("笔", List.of("笔帽", "笔套", "笔袋", "笔架", "笔芯", "墨囊"));
        put("钢笔", List.of("笔帽", "笔套", "笔袋", "笔架", "笔芯", "墨囊", "墨水", "吸墨器"));
        // === 家具 ===
        put("床", List.of("床垫", "床单", "床笠", "床裙", "床头套", "床围"));
        put("桌", List.of("桌布", "桌垫", "桌套", "桌旗"));
        put("椅子", List.of("椅垫", "椅套", "椅背套", "扶手垫", "脚轮"));
        put("沙发", List.of("沙发垫", "沙发套", "沙发巾", "靠垫", "抱枕"));
        put("书架", List.of("书立", "书挡", "书套"));
        // === 清洁工具 ===
        put("拖把", List.of("拖把头", "拖布", "拖把杆", "拖把桶", "拖把夹"));
        put("扫把", List.of("扫把头", "簸箕"));
        // === 浴室 ===
        put("毛巾", List.of("毛巾架", "毛巾挂", "毛巾环", "毛巾杆"));
        put("牙刷", List.of("牙刷架", "牙刷套", "牙刷头", "牙刷杯"));
        // === 健身 ===
        put("哑铃", List.of("哑铃架", "哑铃片", "手套", "护腕"));
        put("瑜伽垫", List.of("瑜伽垫套", "瑜伽垫袋", "瑜伽砖", "瑜伽带"));
        // === 个护电器 ===
        put("剃须刀", List.of("剃须膏", "剃须泡沫", "剃须啫喱", "剃须gel", "刀片", "刀头", "替换头", "保护盖", "收纳盒", "须后水", "须后乳", "充电器", "电池", "充电线", "充电底座"));
        put("电动剃须刀", List.of("剃须膏", "剃须泡沫", "剃须啫喱", "剃须gel", "刀片", "刀头", "替换头", "保护盖", "收纳盒", "须后水", "须后乳", "充电器", "电池", "充电线", "充电底座"));
        put("吹风机", List.of("风嘴", "风罩", "支架", "挂架", "扩散器"));
        put("电动牙刷", List.of("牙膏", "牙线", "漱口水", "刷头", "牙刷架", "牙刷套", "牙刷头"));
        put("冲牙器", List.of("牙膏", "牙线", "漱口水"));
        put("卷发棒", List.of("隔热垫", "夹子", "发夹", "发圈", "喷雾"));
        put("直发器", List.of("隔热垫", "夹子", "发夹", "发圈", "喷雾"));
        put("洁面仪", List.of("洗面奶", "洁面乳", "化妆棉"));
        put("美容仪", List.of("精华液", "面霜", "凝胶", "导入液"));
        put("脱毛仪", List.of("脱毛膏", "剃毛刀", "刮毛刀"));
        put("理发器", List.of("梳子", "围布", "喷雾瓶", "发蜡", "发胶"));
        // === 数码3C ===
        put("手机", List.of("手机壳", "手机膜", "手机支架", "手机袋", "手机套", "手机绳", "手机挂绳", "手机指环", "手机镜头", "手机散热器", "手机手柄"));
        put("耳机", List.of("耳机套", "耳帽", "耳塞", "耳机壳", "耳机包", "耳机线", "耳机架", "耳机收纳"));
        put("智能手表", List.of("表带", "表壳", "表膜", "表链", "表扣", "充电底座", "充电线"));
        put("智能手环", List.of("表带", "表壳", "表膜", "充电底座", "充电线"));
        put("手表", List.of("表带", "表壳", "表膜", "表链", "表扣", "表架", "摇表器"));
        put("相机", List.of("相机包", "相机膜", "相机带", "镜头盖", "遮光罩", "UV镜", "滤镜", "存储卡", "读卡器"));
        put("笔记本", List.of("笔记本支架", "笔记本包", "笔记本壳", "笔记本膜", "键盘膜", "散热底座", "扩展坞"));
        put("平板", List.of("平板壳", "平板膜", "平板支架", "平板套", "触控笔", "键盘"));
        put("显示器", List.of("显示器支架", "显示器挂灯", "屏幕清洁", "防尘罩"));
        put("键盘", List.of("键帽", "掌托", "拔键器", "防尘罩", "清洁泥"));
        put("鼠标", List.of("鼠标垫", "鼠标脚贴", "鼠标防滑贴"));
        put("路由器", List.of("网线", "光纤", "交换机"));
        put("无人机", List.of("螺旋桨", "电池", "充电器", "保护罩", "收纳包"));
        put("投影仪", List.of("幕布", "支架", "吊架", "遥控器", "清洁笔"));
        put("打印机", List.of("墨盒", "硒鼓", "碳粉", "打印纸", "色带"));
        // === 大家电 ===
        put("空调", List.of("空调罩", "空调支架", "空调挡风板", "过滤网", "清洗剂", "遥控器"));
        put("冰箱", List.of("冰箱贴", "冰箱收纳盒", "冰箱除味", "冰箱罩"));
        put("洗衣机", List.of("洗衣机清洁剂", "洗衣机支架", "洗衣液", "洗衣粉", "柔顺剂", "防尘罩"));
        put("电视", List.of("电视挂架", "电视柜", "遥控器", "电视罩", "屏幕清洁"));
        put("热水器", List.of("花洒", "软管", "角阀", "泄压阀"));
        put("洗碗机", List.of("洗碗块", "洗碗粉", "洗碗盐", "漂洗剂", "清洁剂"));
        // === 厨房电器 ===
        put("电饭煲", List.of("量杯", "饭勺", "蒸笼", "内胆", "密封圈"));
        put("微波炉", List.of("微波炉架", "微波炉罩", "保鲜盖", "加热盖"));
        put("烤箱", List.of("烤盘", "烤网", "锡纸", "油纸", "烘焙纸", "手套", "温度计"));
        put("空气炸锅", List.of("炸锅纸", "锡纸", "烤盘", "油纸", "硅胶垫", "喷油壶"));
        put("咖啡机", List.of("咖啡豆", "咖啡粉", "滤纸", "清洁片", "奶泡器", "磨豆机"));
        put("榨汁机", List.of("滤网", "刷子", "杯盖"));
        put("豆浆机", List.of("滤网", "量杯"));
        put("电磁炉", List.of("锅具", "炒锅", "汤锅", "奶锅"));
        // === 清洁/环境电器 ===
        put("吸尘器", List.of("滤芯", "滤网", "尘袋", "吸头", "刷头", "软管", "延长管"));
        put("扫地机器人", List.of("抹布", "边刷", "滚刷", "滤芯", "尘袋", "清洁液", "水箱"));
        put("洗地机", List.of("滚刷", "滤芯", "清洁液", "刷头"));
        put("净水器", List.of("滤芯", "滤芯", "PE管", "接头", "水龙头"));
        put("空气净化器", List.of("滤芯", "滤网", "预滤网"));
        put("加湿器", List.of("滤芯", "滤棒", "清洁剂", "香薰"));
        put("除湿机", List.of("滤网", "水箱", "排水管"));
        // === 生活电器 ===
        put("电风扇", List.of("风扇罩", "风扇叶", "遥控器", "支架"));
        put("挂烫机", List.of("毛刷", "熨烫板", "防烫手套"));
        put("电熨斗", List.of("熨烫板", "喷雾瓶", "防烫手套", "清洁剂"));
        put("按摩椅", List.of("靠垫", "坐垫", "遥控器", "清洁剂"));
        // === 照明 ===
        put("台灯", List.of("灯泡", "灯罩", "灯架", "调光器"));
        put("落地灯", List.of("灯泡", "灯罩", "灯架"));
        put("吊灯", List.of("灯泡", "灯罩", "灯架", "安装件"));
        // === 鞋类 ===
        put("运动鞋", List.of("鞋垫", "鞋带", "鞋油", "鞋刷", "鞋架", "鞋盒", "除臭", "防水喷雾"));
        put("皮鞋", List.of("鞋垫", "鞋油", "鞋刷", "鞋架", "鞋盒", "鞋撑", "防水喷雾"));
        put("篮球鞋", List.of("鞋垫", "鞋带", "鞋油", "鞋刷", "鞋架"));
        put("跑鞋", List.of("鞋垫", "鞋带", "鞋油", "鞋刷", "鞋架"));
        put("高跟鞋", List.of("鞋垫", "鞋油", "鞋刷", "前掌垫", "后跟贴", "防磨贴"));
        put("靴子", List.of("鞋垫", "鞋油", "鞋刷", "靴撑", "防水喷雾"));
        // === 服饰 ===
        put("羽绒服", List.of("洗衣液", "清洗剂", "收纳袋", "防尘袋", "压缩袋"));
        put("西装", List.of("衣架", "防尘袋", "收纳袋", "去球器"));
        put("内衣", List.of("洗衣液", "洗衣袋", "晾衣架"));
        put("泳衣", List.of("泳帽", "泳镜", "耳塞", "鼻夹", "浮板"));
        // === 箱包 ===
        put("行李箱", List.of("箱套", "箱贴", "TSA锁", "打包带", "收纳袋", "万向轮"));
        put("双肩包", List.of("防雨罩", "挂扣", "收纳袋"));
        put("钱包", List.of("卡包", "钥匙包"));
        // === 美妆护肤 ===
        put("口红", List.of("唇线笔", "唇刷", "唇膜", "唇部磨砂", "卸妆"));
        put("粉底液", List.of("美妆蛋", "粉底刷", "散粉", "定妆喷雾", "妆前乳", "隔离"));
        put("眼影", List.of("眼影刷", "眼部打底", "眼线", "睫毛膏"));
        put("面膜", List.of("面膜碗", "面膜刷", "面膜贴", "硅胶面膜"));
        put("防晒霜", List.of("防晒喷雾", "晒后修复", "芦荟胶"));
        put("精华液", List.of("导入仪", "美容仪", "化妆棉"));
        put("洗面奶", List.of("洁面仪", "起泡网", "洗脸巾", "化妆棉"));
        put("香水", List.of("香膏", "香体喷雾", "止汗露"));
        // === 饰品 ===
        put("项链", List.of("项链盒", "首饰盒", "擦银布", "挂绳"));
        put("戒指", List.of("戒指盒", "首饰盒", "量指圈", "擦银布"));
        put("耳环", List.of("耳堵", "耳塞", "首饰盒", "擦银布"));
        put("墨镜", List.of("眼镜盒", "眼镜布", "眼镜链", "鼻托"));
        // === 母婴 ===
        put("奶瓶", List.of("奶嘴", "奶瓶刷", "奶瓶架", "温奶器", "消毒锅", "奶瓶夹"));
        put("纸尿裤", List.of("湿巾", "护臀膏", "隔尿垫", "换尿布垫"));
        put("婴儿车", List.of("蚊帐", "雨罩", "杯架", "置物袋", "凉席", "遮阳蓬"));
        put("安全座椅", List.of("凉席", "防磨垫", "车载收纳"));
        // === 家纺 ===
        put("枕头", List.of("枕套", "枕巾", "枕芯"));
        put("被子", List.of("被套", "被罩", "被袋", "压缩袋"));
        put("床垫", List.of("床笠", "床单", "床裙", "保护垫", "褥子"));
        put("窗帘", List.of("窗帘杆", "窗帘环", "窗帘钩", "绑带", "遮光布"));
        // === 家具 ===
        put("沙发", List.of("沙发垫", "沙发套", "沙发巾", "靠垫", "抱枕"));
        put("餐桌", List.of("桌布", "桌垫", "餐垫", "转盘"));
        put("书桌", List.of("桌垫", "收纳盒", "台灯", "显示器支架"));
        put("办公椅", List.of("坐垫", "靠垫", "扶手垫", "地垫", "脚轮"));
        put("床", List.of("床垫", "床单", "被子", "枕头", "四件套", "床笠"));
        // === 食品 ===
        put("咖啡", List.of("咖啡杯", "咖啡壶", "咖啡机", "咖啡滤纸", "奶精", "糖浆"));
        put("牛奶", List.of("奶锅", "奶杯", "吸管"));
        put("巧克力", List.of("巧克力模具", "包装纸"));
        put("坚果", List.of("坚果盒", "开壳器"));
        put("方便面", List.of("泡面碗", "泡面锅"));
        put("茶叶", List.of("茶壶", "茶杯", "茶盘", "茶具", "茶罐", "茶巾", "茶滤"));
        // === 宠物 ===
        put("猫粮", List.of("猫碗", "猫罐头", "猫零食", "化毛膏"));
        put("狗粮", List.of("狗碗", "狗罐头", "狗零食", "磨牙棒"));
        put("猫砂", List.of("猫砂盆", "猫砂铲", "猫砂垫"));
        // === 汽车 ===
        put("行车记录仪", List.of("存储卡", "支架", "降压线", "后摄像头"));
        put("车载充电器", List.of("数据线", "充电线"));
        put("汽车坐垫", List.of("头枕", "腰靠", "安全带套"));
        put("汽车脚垫", List.of("后备箱垫", "门槛条"));
        // === 办公文具 ===
        put("钢笔", List.of("墨水", "笔尖", "吸墨器", "笔袋"));
        // 打印机已在数码3C中定义，此处不重复
        // === 运动 ===
        put("跑步机", List.of("润滑油", "跑带", "减震垫", "安全锁"));
        put("自行车", List.of("车灯", "车锁", "打气筒", "修车工具", "骑行服", "头盔", "手套"));
        put("瑜伽垫", List.of("瑜伽砖", "瑜伽带", "瑜伽铺巾", "瑜伽服", "瑜伽袜"));
        put("哑铃", List.of("哑铃架", "手套", "护腕", "护肘"));
        // === 工具 ===
        put("电钻", List.of("钻头", "批头", "充电器", "电池", "工具箱"));
        put("电锤", List.of("钻头", "凿子", "工具箱"));
        // === 乐器 ===
        put("吉他", List.of("琴弦", "琴包", "调音器", "拨片", "变调夹", "琴架", "背带", "效果器"));
        put("钢琴", List.of("琴凳", "琴罩", "节拍器", "琴谱架"));
    }};

    /**
     * 统一的产品相关性过滤：检查标题是否与识别属性相关。
     * 核心规则：标题必须包含核心产品词（杯子/手机/鼠标等），否则过滤。
     * 这是根本性的过滤，不依赖品类字段是否为空。
     */
    public static boolean isProductRelevant(String title, Map<String, String> attributes) {
        String normalizedTitle = StringUtils.defaultString(title).toLowerCase(Locale.ROOT);
        if (normalizedTitle.isBlank()) {
            return false;
        }

        // 检查排除词
        String category = useful(attributes == null ? null : attributes.get(ATTR_CATEGORY));
        String coreProduct = coreProductToken(attributes);
        if (StringUtils.isNotBlank(category) && hasExcludedTerms(normalizedTitle, category)) {
            return false;
        }
        if (StringUtils.isNotBlank(coreProduct) && hasExcludedTerms(normalizedTitle, coreProduct)) {
            return false;
        }

        // 通用配件过滤：标题含配件词但品类不是配件类 → 过滤掉
        if (hasAccessoryConflict(normalizedTitle, coreProduct)) {
            return false;
        }

        // 核心过滤：类目或关键词中的任一核心产品词命中即可，降低 VLM 单字段误判带来的误杀。
        List<String> requiredTerms = extractRequiredTerms(attributes);
        if (!requiredTerms.isEmpty()) {
            return requiredTerms.stream()
                    .anyMatch(term -> titleContainsProductFamily(normalizedTitle, term));
        }

        // 无任何有效属性时拒绝（宁可少返回也不返回不相关的）
        return false;
    }

    public static boolean hasExcludedTerms(String normalizedTitle, String category) {
        for (Map.Entry<String, List<String>> entry : SUBCATEGORY_EXCLUSIONS.entrySet()) {
            if (containsNormalized(category, entry.getKey()) || containsNormalized(entry.getKey(), category)) {
                for (String excluded : entry.getValue()) {
                    if (normalizedTitle.contains(excluded.toLowerCase(Locale.ROOT))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 从识别属性中提取"必须包含"的核心产品名词。
     * 优先级：品类(category) > 关键词(keywords)中匹配 CORE_PRODUCT_TERMS 的词
     * 返回的词是标题必须包含的，否则判定为不相关。
     * 例：category="水杯" → "水杯"；keywords="卡通 水杯" → "水杯"
     */
    private static List<String> extractRequiredTerms(Map<String, String> attributes) {
        if (attributes == null) return List.of();
        List<String> terms = new ArrayList<>();
        // 1. 优先从品类中提取
        String category = useful(attributes.get(ATTR_CATEGORY));
        if (StringUtils.isNotBlank(category)) {
            String core = coreProductToken(category);
            if (StringUtils.isNotBlank(core) && core.length() >= 2) {
                terms.add(core);
            } else {
                terms.add(category);
            }
        }
        // 2. 从关键词中找 CORE_PRODUCT_TERM
        String keyword = useful(attributes.get(ATTR_KEYWORD));
        if (StringUtils.isNotBlank(keyword)) {
            String core = coreProductToken(keyword);
            if (StringUtils.isNotBlank(core) && core.length() >= 2) {
                terms.add(core);
            }
        }
        // 3. 从 keywords 列表中找
        String keywords = useful(attributes.get(ATTR_KEYWORDS));
        if (StringUtils.isNotBlank(keywords)) {
            splitSearchTerms(keywords).stream()
                    .map(SearchTextUtils::coreProductToken)
                    .filter(term -> StringUtils.isNotBlank(term) && term.length() >= 2)
                    .forEach(terms::add);
        }
        return terms.stream().map(SearchTextUtils::useful).filter(StringUtils::isNotBlank).distinct().toList();
    }

    public static boolean titleContainsProductFamily(String normalizedTitle, String productTerm) {
        String term = useful(productTerm).toLowerCase(Locale.ROOT);
        if (term.isBlank()) return false;
        if (normalizedTitle.contains(term)) return true;
        return siblingProductTerms(term).stream()
                .map(sibling -> sibling.toLowerCase(Locale.ROOT))
                .anyMatch(normalizedTitle::contains);
    }

    /**
     * 判断标题是否是"配件"而非"主品"。
     * 规则：标题包含核心产品词 AND 包含另一个配件词 → 配件。
     * 关键：配件词 != 核心产品词时才判定，避免搜索"充电器"时把"充电器"本身过滤掉。
     *
     * 例：搜"剃须刀"(core="剃须刀")
     *     "飞利浦剃须刀"       → 含"剃须刀"，不含其他配件词 → 主品 ✅
     *     "飞利浦剃须刀充电器"  → 含"剃须刀" + 含"充电器"(≠剃须刀) → 配件 ❌
     *     搜"充电器"(core="充电器")
     *     "苹果充电器"         → 含"充电器"，配件词"充电器"=core → 主品 ✅
     */
    public static boolean hasAccessoryConflict(String normalizedTitle, String coreProduct) {
        String coreLower = StringUtils.defaultString(coreProduct).toLowerCase(Locale.ROOT);
        if (coreLower.isBlank() || coreLower.length() < 2) return false;
        // 标题必须包含核心产品词才有资格判定为配件
        if (!normalizedTitle.contains(coreLower)) return false;
        // 标题包含核心产品词 + 另一个配件词 → 配件
        for (String accessory : COMMON_ACCESSORY_TERMS) {
            String accLower = accessory.toLowerCase(Locale.ROOT);
            // 关键：配件词不能就是核心产品本身
            if (accLower.equals(coreLower)) continue;
            if (normalizedTitle.contains(accLower)) {
                return true;
            }
        }
        // 通用后缀模式检测：标题以"核心产品词+配件单字"开头 → 配件
        // 例：core="杯", title="杯套xxx" → "杯套"以"杯"开头且"套"是配件后缀 → 配件
        //     core="杯", title="杯子"   → "杯子"以"杯"开头但"子"不是配件后缀 → 通过
        //     core="杯", title="保温杯" → 不以"杯"开头 → 跳过此规则
        if (normalizedTitle.startsWith(coreLower) && normalizedTitle.length() > coreLower.length()) {
            char nextChar = normalizedTitle.charAt(coreLower.length());
            if (ACCESSORY_SUFFIX_CHARS.indexOf(nextChar) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断一个词是否是配件术语。
     * 用于搜索意图判断：如果用户搜索的就是配件（如"手机壳"），则不应过滤配件商品。
     *
     * 判断逻辑：
     * 1. 词在 COMMON_ACCESSORY_TERMS 中（如"保护壳"、"手机膜"）
     * 2. 词匹配 SUBCATEGORY_EXCLUSIONS 中的某个排除项
     * 3. 词以"核心产品词+配件后缀单字"开头（如"手机壳"="手机"+"壳"）
     */
    public static boolean isAccessoryTerm(String term) {
        String lower = useful(term).toLowerCase(Locale.ROOT);
        if (lower.isBlank()) return false;

        // 1. 在通用配件词列表中
        for (String accessory : COMMON_ACCESSORY_TERMS) {
            if (lower.contains(accessory.toLowerCase(Locale.ROOT))) return true;
        }

        // 2. 在 SUBCATEGORY_EXCLUSIONS 的某个排除值列表中
        for (Map.Entry<String, List<String>> entry : SUBCATEGORY_EXCLUSIONS.entrySet()) {
            for (String excluded : entry.getValue()) {
                if (lower.contains(excluded.toLowerCase(Locale.ROOT))) return true;
            }
        }

        // 3. 匹配"核心产品词+配件后缀单字"模式
        for (String core : CORE_PRODUCT_TERMS) {
            String coreLower = core.toLowerCase(Locale.ROOT);
            if (lower.startsWith(coreLower) && lower.length() > coreLower.length()) {
                char nextChar = lower.charAt(coreLower.length());
                if (ACCESSORY_SUFFIX_CHARS.indexOf(nextChar) >= 0) {
                    return true;
                }
            }
        }

        return false;
    }

}
