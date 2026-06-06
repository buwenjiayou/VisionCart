-- 商品快照明细表：一条识别历史对应多条商品
CREATE TABLE IF NOT EXISTS recognition_history_product (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    history_id      VARCHAR(64)   NOT NULL COMMENT '关联 recognition_history.session_id',
    product_id      VARCHAR(128)  NOT NULL,
    sort_no         INT           NOT NULL DEFAULT 0,
    similarity_score DECIMAL(8,6) DEFAULT NULL,
    title           VARCHAR(500)  DEFAULT NULL,
    cover_image     VARCHAR(1000) DEFAULT NULL,
    price           DECIMAL(10,2) DEFAULT NULL,
    original_price  DECIMAL(10,2) DEFAULT NULL,
    platform        VARCHAR(32)   DEFAULT NULL,
    brand           VARCHAR(100)  DEFAULT NULL,
    rating          DECIMAL(3,2)  DEFAULT NULL,
    sales           INT           DEFAULT NULL,
    detail_url      VARCHAR(1000) DEFAULT NULL,
    snapshot_json   JSON          DEFAULT NULL COMMENT '完整快照，备查',
    created_at      DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_rhp_history (history_id),
    INDEX idx_rhp_history_sort (history_id, sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
