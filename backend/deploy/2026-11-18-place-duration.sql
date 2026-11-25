-- Optional when DDL_AUTO is not update. Run in the application's database.
SET @place_duration_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'itinerary_places' AND column_name = 'duration_minutes');
SET @place_duration_sql = IF(@place_duration_exists = 0,
    'ALTER TABLE itinerary_places ADD COLUMN duration_minutes INT NULL',
    'SELECT 1');
PREPARE place_duration_statement FROM @place_duration_sql;
EXECUTE place_duration_statement;
DEALLOCATE PREPARE place_duration_statement;
