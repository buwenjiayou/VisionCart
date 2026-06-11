package com.visioncart.service.search;

import org.springframework.stereotype.Component;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.*;

/**
 * 商品品类注册表：定义主商品、特征词、相关词的分类。
 * 用于 ProductIntentBuilder 构建意图时判断哪些词是主商品、哪些是特征、哪些是相关词。
 *
 * <p>规则：
 * <ul>
 *   <li>primary 词永远是 canonicalProduct 候选</li>
 *   <li>feature 词可以和 primary 组合搜索，但不能单独搜索</li>
 *   <li>relatedOnly 词只能与 primary 组合，不能单独搜索</li>
 * </ul>
 */
@Component
public class ProductTaxonomyRegistry {

    /** 品类定义 */
    private static final Map<String, TaxonomyEntry> TAXONOMY = new LinkedHashMap<>();

    static {
        // === 手机配件 ===
        register("phone_case", "ACCESSORY_MAIN",
                List.of("手机壳", "手机套", "保护壳", "保护套", "手机保护壳", "手机保护套", "case"),
                List.of("磁吸", "MagSafe", "透明", "磨砂", "防摔", "支架", "铝合金", "全包", "超薄",
                        "液态硅胶", "皮套", "凯夫拉", "芳纶", "碳纤维", "翻盖", "简约", "潮牌"),
                List.of("金属环", "磁吸环", "引磁片", "磁吸片", "指环扣", "贴片", "挂绳", "手机绳"),
                List.of("手机", "智能手机"));

        register("screen_protector", "ACCESSORY_MAIN",
                List.of("手机膜", "钢化膜", "屏幕保护膜", "保护膜", "贴膜"),
                List.of("防窥", "防蓝光", "磨砂", "高清", "全屏", "水凝膜", "UV膜", "AR膜"),
                List.of("贴膜工具", "除尘贴", "刮板", "定位器"),
                List.of("手机", "智能手机"));

        // === 耳机配件 ===
        register("earphone_case", "ACCESSORY_MAIN",
                List.of("耳机套", "耳机壳", "耳机保护套", "耳机保护壳", "AirPods套", "AirPods壳"),
                List.of("硅胶", "皮革", "卡通", "防摔", "挂绳", "充电仓保护"),
                List.of("耳帽", "耳塞", "替换头", "清洁刷"),
                List.of("耳机", "蓝牙耳机", "无线耳机", "AirPods"));

        // === 手表配件 ===
        register("watch_band", "ACCESSORY_MAIN",
                List.of("表带", "手表带", "手环带", "表链"),
                List.of("米兰尼斯", "硅胶", "皮革", "不锈钢", "尼龙", "编织", "回环"),
                List.of("表壳", "表膜", "充电底座", "表扣"),
                List.of("手表", "智能手表", "手环", "Apple Watch"));

        // === 电脑配件 ===
        register("laptop_bag", "ACCESSORY_MAIN",
                List.of("电脑包", "笔记本包", "内胆包", "手提包", "双肩电脑包"),
                List.of("防震", "防水", "商务", "轻便", "大容量"),
                List.of("鼠标垫", "键盘膜", "散热底座", "支架"),
                List.of("笔记本", "电脑", "MacBook"));

        register("keyboard_skin", "ACCESSORY_MAIN",
                List.of("键盘膜", "键盘保护膜", "键盘套"),
                List.of("硅胶", "TPU", "透明", "彩色"),
                List.of("键帽", "掌托", "拔键器"),
                List.of("键盘", "机械键盘"));

        // === 相机配件 ===
        register("camera_bag", "ACCESSORY_MAIN",
                List.of("相机包", "摄影包", "镜头包", "内胆包"),
                List.of("防水", "防震", "单肩", "双肩", "斜挎"),
                List.of("镜头盖", "UV镜", "遮光罩", "肩带"),
                List.of("相机", "单反", "微单"));

        // === 平板配件 ===
        register("tablet_case", "ACCESSORY_MAIN",
                List.of("平板壳", "平板保护套", "iPad壳", "iPad套", "平板支架"),
                List.of("硅胶", "皮套", "防摔", "旋转", "带笔槽"),
                List.of("平板膜", "触控笔", "键盘"),
                List.of("平板", "iPad"));

        // === 手机（主商品）===
        register("phone", "MAIN_PRODUCT",
                List.of("手机", "智能手机"),
                List.of("5G", "全面屏", "折叠屏", "快充", "长续航", "游戏手机"),
                List.of("手机壳", "手机膜", "充电器", "数据线", "耳机"),
                List.of());

        // === 耳机（主商品）===
        register("earbuds", "MAIN_PRODUCT",
                List.of("耳机", "蓝牙耳机", "无线耳机", "降噪耳机", "头戴式耳机", "入耳式耳机"),
                List.of("主动降噪", "ANC", "通透模式", "低延迟", "长续航", "Hi-Res"),
                List.of("耳机套", "耳机壳", "耳帽", "耳塞", "充电盒"),
                List.of());

        // === 充电宝 / 移动电源 ===
        register("power_bank", "MAIN_PRODUCT",
                List.of("充电宝", "移动电源", "应急电源", "户外电源", "便携充电器"),
                List.of("快充", "大容量", "10000mAh", "20000mAh", "20Ah", "PD", "QC", "磁吸", "无线充电"),
                List.of("保护套", "收纳袋", "数据线", "充电线", "充电器"),
                List.of());

        // === 鞋类 ===
        register("shoe", "MAIN_PRODUCT",
                List.of("运动鞋", "跑鞋", "篮球鞋", "板鞋", "休闲鞋", "皮鞋", "拖鞋", "凉鞋",
                        "高跟鞋", "帆布鞋", "靴子", "登山鞋"),
                List.of("气垫", "碳板", "Boost", "飞织", "防水", "透气", "减震", "防滑"),
                List.of("鞋垫", "鞋带", "鞋油", "鞋刷", "鞋撑"),
                List.of());

        // === 服饰 ===
        register("clothing", "MAIN_PRODUCT",
                List.of("T恤", "衬衫", "外套", "夹克", "风衣", "羽绒服", "卫衣", "毛衣",
                        "牛仔裤", "休闲裤", "运动裤", "连衣裙", "短裤"),
                List.of("纯棉", "速干", "防水", "保暖", "透气", "弹力"),
                List.of("衣架", "洗衣液", "柔顺剂"),
                List.of());
    }

    public ProductTaxonomyRegistry() {
        loadExternalTaxonomy();
    }

    private void loadExternalTaxonomy() {
        ClassPathResource resource = new ClassPathResource("search-taxonomy.yml");
        if (!resource.exists()) {
            return;
        }
        try (InputStream input = resource.getInputStream()) {
            Object loaded = new Yaml().load(input);
            if (!(loaded instanceof Map<?, ?> root)) {
                return;
            }
            Object families = root.get("families");
            if (!(families instanceof Map<?, ?> familyMap)) {
                return;
            }
            for (Map.Entry<?, ?> familyEntry : familyMap.entrySet()) {
                String family = Objects.toString(familyEntry.getKey(), "");
                if (!(familyEntry.getValue() instanceof Map<?, ?> rawEntry) || family.isBlank()) {
                    continue;
                }
                Object roleValue = rawEntry.containsKey("role") ? rawEntry.get("role") : "MAIN_PRODUCT";
                Object relatedValue = rawEntry.containsKey("related_only")
                        ? rawEntry.get("related_only") : rawEntry.get("relatedOnly");
                Object parentValue = rawEntry.containsKey("parent_primary")
                        ? rawEntry.get("parent_primary") : rawEntry.get("parentPrimary");
                String role = Objects.toString(roleValue, "MAIN_PRODUCT");
                TAXONOMY.put(family, new TaxonomyEntry(
                        family,
                        ProductIntent.ProductRole.valueOf(role),
                        yamlList(rawEntry.get("primary")),
                        yamlList(rawEntry.get("features")),
                        yamlList(relatedValue),
                        yamlList(parentValue)
                ));
            }
        } catch (Exception ignored) {
            // Keep the Java fallback taxonomy if the external config is malformed.
        }
    }

    private List<String> yamlList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(item -> Objects.toString(item, ""))
                .filter(item -> !item.isBlank())
                .toList();
    }

    private static void register(String family, String role,
                                  List<String> primary, List<String> features,
                                  List<String> relatedOnly, List<String> parentPrimary) {
        TAXONOMY.put(family, new TaxonomyEntry(
                family,
                ProductIntent.ProductRole.valueOf(role),
                primary, features, relatedOnly, parentPrimary));
    }

    /**
     * 根据关键词/品类匹配最合适的品类条目。
     * 优先精确匹配 primary，然后模糊匹配。
     */
    public TaxonomyEntry resolve(String category, List<String> keywords) {
        // 1. 精确匹配 primary
        for (TaxonomyEntry entry : TAXONOMY.values()) {
            for (String p : entry.primary()) {
                if (p.equals(category) || keywords.contains(p)) {
                    return entry;
                }
            }
        }
        // 2. 包含匹配
        String catLower = category == null ? "" : category;
        for (TaxonomyEntry entry : TAXONOMY.values()) {
            for (String p : entry.primary()) {
                if (catLower.contains(p) || p.contains(catLower)) {
                    return entry;
                }
            }
            for (String kw : keywords) {
                for (String p : entry.primary()) {
                    if (kw.contains(p) || p.contains(kw)) {
                        return entry;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 判断一个词是否是某个品类的 relatedOnly 词。
     */
    public boolean isRelatedOnly(String term) {
        if (term == null || term.isBlank()) return false;
        for (TaxonomyEntry entry : TAXONOMY.values()) {
            for (String r : entry.relatedOnly()) {
                if (term.contains(r) || r.contains(term)) return true;
            }
        }
        return false;
    }

    /**
     * 判断一个词是否是某个品类的 feature 词。
     */
    public boolean isFeature(String term) {
        if (term == null || term.isBlank()) return false;
        for (TaxonomyEntry entry : TAXONOMY.values()) {
            for (String f : entry.features()) {
                if (term.contains(f) || f.contains(term)) return true;
            }
        }
        return false;
    }

    /**
     * 从一段识别文本中提取已知 feature 词。
     * 例："磁吸式防摔壳" → ["磁吸", "防摔"]，避免把整串长描述当成搜索特征。
     */
    public List<String> matchingFeatures(String text) {
        String useful = SearchTextUtils.useful(text);
        if (useful.isBlank()) return List.of();
        LinkedHashSet<String> matches = new LinkedHashSet<>();
        for (TaxonomyEntry entry : TAXONOMY.values()) {
            for (String feature : entry.features()) {
                if (SearchTextUtils.containsNormalized(useful, feature)
                        || SearchTextUtils.containsNormalized(feature, useful)) {
                    matches.add(feature);
                }
            }
        }
        return new ArrayList<>(matches);
    }

    /**
     * 判断一个词是否是主商品词（任何品类的 primary）。
     */
    public boolean isPrimary(String term) {
        if (term == null || term.isBlank()) return false;
        for (TaxonomyEntry entry : TAXONOMY.values()) {
            for (String p : entry.primary()) {
                if (term.equals(p) || term.contains(p) || p.contains(term)) return true;
            }
        }
        return false;
    }

    /** 获取所有品类的 relatedOnly 词（用于禁止单独查询） */
    public Set<String> allRelatedOnlyTerms() {
        Set<String> all = new LinkedHashSet<>();
        for (TaxonomyEntry entry : TAXONOMY.values()) {
            all.addAll(entry.relatedOnly());
        }
        return all;
    }

    /** 获取所有品类的 primary 词 */
    public Set<String> allPrimaryTerms() {
        Set<String> all = new LinkedHashSet<>();
        for (TaxonomyEntry entry : TAXONOMY.values()) {
            all.addAll(entry.primary());
        }
        return all;
    }

    public record TaxonomyEntry(
            String family,
            ProductIntent.ProductRole role,
            List<String> primary,
            List<String> features,
            List<String> relatedOnly,
            List<String> parentPrimary
    ) {}
}
