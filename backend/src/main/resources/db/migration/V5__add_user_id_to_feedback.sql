-- Add user_id column to recognition_feedback (was missing from original table)
ALTER TABLE recognition_feedback
    ADD COLUMN user_id BIGINT DEFAULT NULL AFTER user_correction;
