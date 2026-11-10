-- Run against the application's MySQL database before starting the new JAR.
-- Only retired integration data is removed. Travel plans and login accounts are retained.
USE travel_db;

DROP TABLE IF EXISTS trip_calendar_events;

SET @remove_talk_calendar_sql = (
    SELECT IF(COUNT(*) > 0,
        'ALTER TABLE users DROP COLUMN kakao_token_bundle', 'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'kakao_token_bundle'
);
PREPARE remove_talk_calendar_field FROM @remove_talk_calendar_sql;
EXECUTE remove_talk_calendar_field;
DEALLOCATE PREPARE remove_talk_calendar_field;

SET @remove_talk_calendar_sql = (
    SELECT IF(COUNT(*) > 0,
        'ALTER TABLE users DROP COLUMN kakao_calendar_connected', 'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'kakao_calendar_connected'
);
PREPARE remove_talk_calendar_field FROM @remove_talk_calendar_sql;
EXECUTE remove_talk_calendar_field;
DEALLOCATE PREPARE remove_talk_calendar_field;
