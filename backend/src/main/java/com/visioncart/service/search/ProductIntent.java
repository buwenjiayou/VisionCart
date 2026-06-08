package com.visioncart.service.search;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;
import java.util.Map;

/**
 * 结构化商品意图：从识别结果中提取的标准化搜索意图。
 * 解决扁平 attributes 无法表达"品牌是制造商品牌还是适配品牌"等问题。
 *
 * <p>核心原则：
 * <ul>
 *   <li>先确定主商品，再生成搜索词</li>
 *   <li>品牌按场景解释（主商品=制造商品牌，配件=适配品牌）</li>
 *   <li>局部特征不能当主搜索词</li>
 * </ul>
 */
public record ProductIntent(
        String sessionId,

        // === 主商品 ===
        /** 归一化主商品名：手机壳、无线耳机、鼠标、冲锋衣 */
        String canonicalProduct,
        /** 产品族 code：phone_case, earbuds, mouse, shoe 等 */
        String productFamily,
        /** 产品角色 */
        ProductRole productRole,

        // === 品牌语义 ===
        /** 商品自身品牌：Apple、Nike、倍思 */
        String productBrand,
        /** 适配品牌：iQOO 手机壳里的 iQOO */
        String compatibleBrand,
        /** 适配型号：iPhone 15 Pro、AirPods Pro */
        String model,

        // === 品牌可靠性 ===
        /** 品牌是否可靠（用户修正或 verified=true 的高置信度识别） */
        boolean brandReliable,

        // === 属性 ===
        /** 硬约束属性：类目、型号、容量、尺码（必须匹配） */
        Map<String, String> hardAttributes,
        /** 软约束属性：颜色、材质、风格（可加分） */
        Map<String, String> softAttributes,
        /** 特征词：磁吸、无线、快充、防水（可与主商品组合搜索） */
        List<String> featureTerms,
        /** 相关词：金属环、引磁片、替换头（只能与主商品组合，不能单独搜索） */
        List<String> relatedOnlyTerms,
        /** 负面词：二手、维修、教程（排除） */
        List<String> negativeTerms,

        double confidence,
        String source
) {
    /**
     * 商品角色定义。
     * ACCESSORY_MAIN 也是主商品——用户拍"手机壳"，手机壳就是主商品。
     */
    public enum ProductRole {
        /** 手机、耳机、鼠标、鞋、衣服、相机 */
        MAIN_PRODUCT,
        /** 手机壳、手机膜、电脑包、表带、耳机套 */
        ACCESSORY_MAIN,
        /** 金属环、引磁片、滤芯、刀头、电池 */
        PART,
        /** 墨盒、咖啡胶囊、清洁液、纸巾 */
        CONSUMABLE,
        /** 套装、礼盒、组合装 */
        BUNDLE,
        /** 维修、安装、贴膜服务 */
        SERVICE
    }

    /** 是否是配件主品（手机壳、手机膜等） */
    @JsonIgnore
    public boolean isAccessoryMain() {
        return productRole == ProductRole.ACCESSORY_MAIN;
    }

    /** 是否是主商品（MAIN_PRODUCT 或 ACCESSORY_MAIN） */
    @JsonIgnore
    public boolean isMainProduct() {
        return productRole == ProductRole.MAIN_PRODUCT || productRole == ProductRole.ACCESSORY_MAIN;
    }

    /** 获取最佳品牌：配件场景优先适配品牌，主商品场景优先制造商品牌 */
    @JsonIgnore
    public String bestBrand() {
        if (isAccessoryMain()) {
            return useful(compatibleBrand, productBrand);
        }
        return useful(productBrand, compatibleBrand);
    }

    /**
     * 品牌是否可靠。
     * 品牌非空即视为可靠。brandReliable 字段供策略层做更细粒度的判断。
     */
    @JsonIgnore
    public boolean hasReliableBrand() {
        return brandReliable && !bestBrand().isBlank();
    }

    /**
     * 返回去掉品牌硬约束的副本（用于策略降级）。
     * 保留品类、特征词等其他约束，只放宽品牌。
     */
    public ProductIntent withRelaxedBrand() {
        return new ProductIntent(
                sessionId, canonicalProduct, productFamily, productRole,
                "", "", model, false,
                hardAttributes, softAttributes,
                featureTerms, relatedOnlyTerms, negativeTerms,
                confidence, source
        );
    }

    private static String useful(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return "";
    }
}
