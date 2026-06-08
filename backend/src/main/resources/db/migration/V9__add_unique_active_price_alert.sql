-- Enforce one active price alert per user/product while keeping inactive history.

UPDATE price_alert pa
JOIN (
    SELECT user_id, product_id, MAX(id) AS keep_id
    FROM price_alert
    WHERE active = b'1'
    GROUP BY user_id, product_id
    HAVING COUNT(*) > 1
) dup ON pa.user_id = dup.user_id
    AND pa.product_id = dup.product_id
    AND pa.active = b'1'
    AND pa.id <> dup.keep_id
SET pa.active = b'0';

ALTER TABLE price_alert
    ADD COLUMN active_product_key VARCHAR(255)
        GENERATED ALWAYS AS (
            CASE WHEN active = b'1' THEN product_id ELSE NULL END
        ) STORED,
    ADD UNIQUE KEY uk_price_alert_active_user_product (user_id, active_product_key);
