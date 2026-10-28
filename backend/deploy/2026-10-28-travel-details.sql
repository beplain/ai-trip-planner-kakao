-- EC2 DDL_AUTO=validate 사용 시 새 JAR 시작 전에 실행합니다.
-- 적용 전 mysqldump 등으로 백업하세요. 기존 행은 NULL로 유지됩니다.
-- 아래 ALTER 문은 각각 한 번만 실행합니다.
USE travel_db;
ALTER TABLE trips ADD COLUMN travel_details LONGTEXT NULL;
ALTER TABLE itinerary_places ADD COLUMN fixed_event_id VARCHAR(100) NULL;
