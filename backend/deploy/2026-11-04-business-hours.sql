USE travel_db;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='itinerary_places' AND column_name='business_hours_json')=0,
 'ALTER TABLE itinerary_places ADD COLUMN business_hours_json LONGTEXT NULL', 'SELECT 1');
PREPARE statement FROM @ddl;
EXECUTE statement;
DEALLOCATE PREPARE statement;
