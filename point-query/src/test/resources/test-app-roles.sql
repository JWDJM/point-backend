-- 테스트 전용: 운영의 point_query 계정 (point-infra scripts/db/app-roles.sql). 권한은 Flyway afterMigrate.sql 이 건다
CREATE ROLE point_query LOGIN PASSWORD 'point_query_test';
