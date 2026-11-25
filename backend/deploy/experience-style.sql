-- MySQL: back up the database and stop ai-trip-planner before running this script.
-- Replaces exact legacy style values, not user-written notes or place names.
START TRANSACTION;
UPDATE trips SET styles = TRIM(BOTH '\n' FROM REPLACE(CONCAT('\n', styles, '\n'), '\n액티비티\n', '\n체험\n')),
 version = COALESCE(version, 0) + 1
 WHERE FIND_IN_SET('액티비티', REPLACE(styles, '\n', ',')) > 0;
UPDATE draft_rooms SET
 content = REPLACE(content, '"액티비티"', '"체험"'),
 regeneration_history = REPLACE(regeneration_history, '"액티비티"', '"체험"'),
 deleted_places = REPLACE(deleted_places, '"액티비티"', '"체험"'),
 version = COALESCE(version, 0) + 1
 WHERE content LIKE '%"액티비티"%' OR regeneration_history LIKE '%"액티비티"%' OR deleted_places LIKE '%"액티비티"%';
UPDATE trips SET deleted_places = REPLACE(deleted_places, '"액티비티"', '"체험"'),
 version = COALESCE(version, 0) + 1 WHERE deleted_places LIKE '%"액티비티"%';
UPDATE itinerary_places SET category = '체험' WHERE category = '액티비티';
COMMIT;
