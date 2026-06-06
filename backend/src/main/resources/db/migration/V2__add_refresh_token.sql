-- Refresh token table for JWT refresh flow
CREATE TABLE IF NOT EXISTS refresh_token (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    token_hash  VARCHAR(64)  NOT NULL,
    expires_at  DATETIME(6)  NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    revoked_at  DATETIME(6)  DEFAULT NULL,
    device_info VARCHAR(256) DEFAULT NULL,
    PRIMARY KEY (id),
    INDEX idx_rt_user (user_id),
    INDEX idx_rt_token_hash (token_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
