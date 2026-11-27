-- 테스트 전용: 운영의 point_command 계정 (point-infra scripts/db/app-roles.sql). 권한은 Flyway afterMigrate.sql 이 건다
CREATE ROLE point_command LOGIN PASSWORD 'point_command_test';
