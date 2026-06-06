-- ============================================================
-- VisionCart – V1 基础 schema
-- 对应 JPA 实体: User, RecognitionHistory, FavoriteProduct,
--   PriceAlert, PriceHistory, RecognitionFeedback
-- ============================================================

-- 1. 用户表
CREATE TABLE IF NOT EXISTS vc_user (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    email      VARCHAR(128) NOT NULL,
    settings   VARCHAR(32)  DEFAULT NULL,
    enabled    BIT(1)       NOT NULL DEFAULT b'1',
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_vc_user_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 2. 识别历史表
CREATE TABLE IF NOT EXISTS recognition_history (
    session_id                VARCHAR(64)  NOT NULL,
    image_url                 VARCHAR(512) DEFAULT NULL,
    image_hash                VARCHAR(64)  DEFAULT NULL,
    category_json             TEXT         DEFAULT NULL,
    attributes_json           TEXT         DEFAULT NULL,
    keywords                  VARCHAR(512) DEFAULT NULL,
    confidence                DOUBLE       NOT NULL DEFAULT 0.0,
    user_id                   BIGINT       NOT NULL,
    display_products_snapshot TEXT         DEFAULT NULL,
    applied_filters_json      TEXT         DEFAULT NULL,
    created_at                DATETIME(6)  DEFAULT NULL,
    updated_at                DATETIME(6)  DEFAULT NULL,
    PRIMARY KEY (session_id),
    INDEX idx_history_user (user_id),
    INDEX idx_history_created (created_at DESC),
    INDEX idx_history_user_created (user_id, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 3. 收藏表
CREATE TABLE IF NOT EXISTS favorite (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    product_id VARCHAR(255)  NOT NULL,
    user_id    BIGINT        NOT NULL,
    platform   VARCHAR(255)  DEFAULT NULL,
    title      VARCHAR(255)  DEFAULT NULL,
    image_url  VARCHAR(255)  DEFAULT NULL,
    price      DECIMAL(19,2) DEFAULT NULL,
    detail_url VARCHAR(255)  DEFAULT NULL,
    created_at DATETIME(6)   DEFAULT NULL,
    updated_at DATETIME(6)   DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_favorite_product_user (product_id, user_id),
    INDEX idx_favorite_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 4. 价格提醒表
CREATE TABLE IF NOT EXISTS price_alert (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    product_id    VARCHAR(255)  NOT NULL,
    platform      VARCHAR(255)  NOT NULL,
    target_price  DECIMAL(19,2) NOT NULL,
    current_price DECIMAL(19,2) DEFAULT NULL,
    active        BIT(1)        NOT NULL DEFAULT b'1',
    triggered_at  DATETIME(6)   DEFAULT NULL,
    created_at    DATETIME(6)   DEFAULT NULL,
    user_id       BIGINT        NOT NULL,
    notified_at   DATETIME(6)   DEFAULT NULL,
    PRIMARY KEY (id),
    INDEX idx_alert_user_active (user_id, active),
    INDEX idx_alert_user_product (user_id, product_id, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 5. 价格历史表
CREATE TABLE IF NOT EXISTS price_history (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    product_id  VARCHAR(255)  NOT NULL,
    platform    VARCHAR(255)  NOT NULL,
    price       DECIMAL(19,2) NOT NULL,
    recorded_at DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_product_time (product_id, platform, recorded_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 6. 识别反馈表
CREATE TABLE IF NOT EXISTS recognition_feedback (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    session_id      VARCHAR(64)  NOT NULL,
    image_hash      VARCHAR(64)  DEFAULT NULL,
    attribute_name  VARCHAR(128) NOT NULL,
    vlm_output      TEXT         DEFAULT NULL,
    user_correction TEXT         DEFAULT NULL,
    user_id         BIGINT       DEFAULT NULL,
    created_at      DATETIME(6)  DEFAULT NULL,
    PRIMARY KEY (id),
    INDEX idx_feedback_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
