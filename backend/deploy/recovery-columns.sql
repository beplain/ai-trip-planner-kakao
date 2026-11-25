-- Select the application's existing database in DBeaver before running.
-- Additive and repeatable; preserves all existing trip/draft rows.
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='trips' AND column_name='deleted_at')=0,
 'ALTER TABLE trips ADD COLUMN deleted_at DATETIME(6) NULL','SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='trips' AND column_name='deleted_places')=0,
 'ALTER TABLE trips ADD COLUMN deleted_places LONGTEXT NULL','SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='draft_rooms' AND column_name='deleted_at')=0,
 'ALTER TABLE draft_rooms ADD COLUMN deleted_at DATETIME(6) NULL','SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='draft_rooms' AND column_name='deleted_places')=0,
 'ALTER TABLE draft_rooms ADD COLUMN deleted_places LONGTEXT NULL','SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
