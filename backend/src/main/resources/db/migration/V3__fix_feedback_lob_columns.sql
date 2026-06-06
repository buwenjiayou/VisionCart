-- Fix recognition_feedback columns to match @Lob entity definitions
-- Hibernate @Lob on String maps to LONGTEXT in MySQL.
ALTER TABLE recognition_feedback
    MODIFY COLUMN vlm_output LONGTEXT,
    MODIFY COLUMN user_correction LONGTEXT;
