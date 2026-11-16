-- Apply to the travel planner database before deploying with DDL_AUTO=validate.
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'draft_rooms' AND column_name = 'regeneration_history') = 0,
  'ALTER TABLE draft_rooms ADD COLUMN regeneration_history LONGTEXT NULL', 'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
