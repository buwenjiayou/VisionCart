-- 结构化商品角色字段：支持搜索质量分析（主品/配件/消耗品分布）
ALTER TABLE recognition_history_product
    ADD COLUMN main_category_code VARCHAR(64) DEFAULT NULL COMMENT '归一化品类 code: cup, phone, shoe 等' AFTER snapshot_json,
    ADD COLUMN product_role       VARCHAR(32) DEFAULT NULL COMMENT '商品角色: main, accessory, consumable, unknown' AFTER main_category_code;
