-- Hibernate @Lob on String expects LONGTEXT, not TEXT
ALTER TABLE recognition_feedback
    MODIFY COLUMN vlm_output LONGTEXT,
    MODIFY COLUMN user_correction LONGTEXT;
