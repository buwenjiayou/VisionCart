-- Add user_id column to recognition_feedback (was missing from some early schemas)
SET @column_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'recognition_feedback'
      AND COLUMN_NAME = 'user_id'
);

SET @add_user_id_sql := IF(
    @column_exists = 0,
    'ALTER TABLE recognition_feedback ADD COLUMN user_id BIGINT DEFAULT NULL AFTER user_correction',
    'SELECT 1'
);

PREPARE add_user_id_stmt FROM @add_user_id_sql;
EXECUTE add_user_id_stmt;
DEALLOCATE PREPARE add_user_id_stmt;
