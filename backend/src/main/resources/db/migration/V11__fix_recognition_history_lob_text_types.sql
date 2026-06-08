-- Align recognition_history LOB columns with their JPA TEXT mappings.

ALTER TABLE recognition_history
    MODIFY COLUMN category_json TEXT DEFAULT NULL,
    MODIFY COLUMN attributes_json TEXT DEFAULT NULL,
    MODIFY COLUMN display_products_snapshot TEXT DEFAULT NULL;
