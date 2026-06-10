package com.visioncart.service.recognition;

import com.visioncart.service.search.SearchTextUtils;

import java.util.List;
import java.util.Locale;

final class AttributeOptionCatalog {
    private static final List<String> COLORS = List.of(
            "黑色", "白色", "灰色", "银色", "金色", "红色", "蓝色", "深蓝色", "绿色", "黄色", "粉色", "紫色",
            "棕色", "米白色", "卡其色", "透明"
    );

    private AttributeOptionCatalog() {
    }

    static List<String> optionsFor(String category, String attribute) {
        String usefulAttribute = SearchTextUtils.useful(attribute);
        if (SearchTextUtils.ATTR_COLOR.equals(usefulAttribute)) {
            return COLORS;
        }
        if (!SearchTextUtils.ATTR_BRAND.equals(usefulAttribute)) {
            return List.of();
        }
        return brandOptionsFor(normalize(category));
    }

    static boolean allows(String category, String attribute, String option) {
        String usefulOption = SearchTextUtils.useful(option);
        if (usefulOption.isBlank()) {
            return false;
        }
        return optionsFor(category, attribute).stream()
                .anyMatch(candidate -> sameOption(candidate, usefulOption));
    }

    private static List<String> brandOptionsFor(String category) {
        if (hasAny(category, "剃须", "须刀", "刮胡", "个护", "电动牙刷", "吹风", "美容仪")) {
            return List.of("Philips", "飞利浦", "Braun", "博朗", "Panasonic", "松下", "飞科", "奔腾",
                    "须眉", "素士", "米家", "小米", "超人", "Oral-B", "欧乐B", "Dyson", "戴森");
        }
        if (hasAny(category, "手机", "平板", "pad", "iphone")) {
            return List.of("Apple", "苹果", "Huawei", "华为", "Xiaomi", "小米", "OPPO", "vivo",
                    "Samsung", "三星", "Honor", "荣耀", "OnePlus", "一加", "Realme", "真我", "iQOO", "魅族");
        }
        if (hasAny(category, "耳机", "音箱", "音频", "音响")) {
            return List.of("Apple", "苹果", "Sony", "索尼", "Bose", "JBL", "Sennheiser", "森海塞尔",
                    "Huawei", "华为", "Xiaomi", "小米", "Beats", "Marshall", "马歇尔", "Edifier", "漫步者");
        }
        if (hasAny(category, "电脑", "笔记本", "外设", "鼠标", "键盘", "显示器")) {
            return List.of("Apple", "苹果", "Huawei", "华为", "Xiaomi", "小米", "Lenovo", "联想",
                    "Dell", "戴尔", "HP", "惠普", "ASUS", "华硕", "Acer", "宏碁", "Microsoft", "微软",
                    "ThinkPad", "Logitech", "罗技", "Razer", "雷蛇", "ZOWIE", "卓威");
        }
        if (hasAny(category, "手表", "手环", "穿戴")) {
            return List.of("Apple", "苹果", "Huawei", "华为", "Xiaomi", "小米", "Samsung", "三星",
                    "Garmin", "佳明", "OPPO", "vivo", "Fitbit", "Amazfit", "华米");
        }
        if (hasAny(category, "相机", "镜头", "摄影", "摄像")) {
            return List.of("Canon", "佳能", "Sony", "索尼", "Nikon", "尼康", "Fujifilm", "富士",
                    "Panasonic", "松下", "DJI", "大疆", "GoPro", "Leica", "徕卡");
        }
        if (hasAny(category, "鞋", "靴", "拖鞋", "凉鞋")) {
            return List.of("Nike", "耐克", "Adidas", "阿迪达斯", "李宁", "安踏", "特步", "PUMA", "彪马",
                    "New Balance", "新百伦", "FILA", "斐乐", "Converse", "匡威", "Vans", "范斯",
                    "Reebok", "锐步", "Skechers", "斯凯奇", "ASICS", "亚瑟士");
        }
        if (hasAny(category, "服装", "衣服", "上衣", "裤", "裙", "t恤", "衬衫", "夹克", "外套", "卫衣", "毛衣", "西装")) {
            return List.of("Nike", "耐克", "Adidas", "阿迪达斯", "优衣库", "UNIQLO", "ZARA", "H&M",
                    "李宁", "安踏", "特步", "PUMA", "GAP", "Levi's", "李维斯", "MUJI", "无印良品");
        }
        if (hasAny(category, "包包", "箱包", "手提包", "背包", "行李箱", "行李")) {
            return List.of("LV", "Louis Vuitton", "路易威登", "Gucci", "古驰", "Chanel", "香奈儿",
                    "Coach", "蔻驰", "Michael Kors", "MK", "Longchamp", "珑骧", "Samsonite", "新秀丽");
        }
        if (hasAny(category, "家电", "冰箱", "洗衣机", "空调", "电视", "厨", "清洁", "吸尘", "扫地")) {
            return List.of("美的", "格力", "海尔", "小米", "米家", "Samsung", "三星", "LG", "Sony", "索尼",
                    "Panasonic", "松下", "西门子", "博世", "Dyson", "戴森", "九阳", "苏泊尔", "石头", "追觅", "科沃斯");
        }
        if (hasAny(category, "美妆", "护肤", "化妆", "口红", "面膜", "精华", "防晒")) {
            return List.of("兰蔻", "Lancome", "雅诗兰黛", "Estee Lauder", "SK-II", "欧莱雅", "L'Oreal",
                    "资生堂", "Shiseido", "完美日记", "花西子", "珀莱雅", "薇诺娜", "MAC", "YSL", "Dior", "Chanel");
        }
        if (hasAny(category, "食品", "零食", "饮料", "茶", "咖啡", "酒")) {
            return List.of("三只松鼠", "良品铺子", "百草味", "蒙牛", "伊利", "康师傅", "农夫山泉",
                    "元气森林", "星巴克", "雀巢", "可口可乐", "百事", "统一");
        }
        if (hasAny(category, "母婴", "玩具", "婴儿", "童装", "纸尿裤", "奶粉")) {
            return List.of("babycare", "好孩子", "帮宝适", "花王", "美赞臣", "飞鹤", "乐高", "LEGO",
                    "费雪", "Fisher-Price", "巴拉巴拉", "安奈儿");
        }
        if (hasAny(category, "汽车", "车载")) {
            return List.of("Tesla", "特斯拉", "比亚迪", "宝马", "BMW", "奔驰", "Mercedes", "大众",
                    "丰田", "本田", "蔚来", "小鹏", "理想");
        }
        if (hasAny(category, "宠物", "猫粮", "狗粮", "猫砂", "猫咪", "狗狗")) {
            return List.of("皇家", "Royal Canin", "渴望", "Orijen", "冠能", "Pro Plan", "网易严选",
                    "麦富迪", "卫仕");
        }
        return List.of();
    }

    private static boolean hasAny(String category, String... needles) {
        for (String needle : needles) {
            if (category.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameOption(String left, String right) {
        return normalizeOption(left).equals(normalizeOption(right));
    }

    private static String normalize(String value) {
        return SearchTextUtils.useful(value).toLowerCase(Locale.ROOT);
    }

    private static String normalizeOption(String value) {
        return SearchTextUtils.useful(value)
                .replace(" ", "")
                .replace("-", "")
                .toLowerCase(Locale.ROOT);
    }
}
