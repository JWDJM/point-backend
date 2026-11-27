-- 테스트 전용: point-infra scripts/db/app-roles.sql 이 운영에서 만드는 계정과 같은 이름 (비밀번호·DB 권한은 생략)
CREATE ROLE user_service;
CREATE ROLE point_command;
CREATE ROLE point_query;
CREATE ROLE app_rw;
