-- Align recognition_history.applied_filters_json with the JPA TEXT mapping.

ALTER TABLE recognition_history
    MODIFY COLUMN applied_filters_json TEXT DEFAULT NULL;
