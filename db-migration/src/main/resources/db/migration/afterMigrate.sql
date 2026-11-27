-- Flyway callback: 마이그레이션이 끝날 때마다 실행 (적용할 V 파일이 없어도 실행된다). 멱등.
-- 서비스별 스키마·테이블 권한 (point-infra ADR-0024, point-backend#26)
--
-- 계정은 point-infra scripts/db/app-roles.sql 이 만든다. 계정이 없는 DB(로컬 docker·테스트)에서는 그 계정의 GRANT 를 건너뛴다.
-- 테이블을 새로 만들어도 다음 마이그레이션 때 여기서 권한이 다시 걸린다 → 새 테이블마다 GRANT 를 따로 쓰지 않는다.
-- 논리 복제는 GRANT 를 복제하지 않는다 → Cloud SQL 에는 DR 승격 후 db-migration Job 이 같은 권한을 건다.
--
-- ⚠️ 서비스 계정에 권한을 추가하는 것은 이 파일에서만 한다. V 파일에 GRANT 를 쓰지 않는다.
DO $$
BEGIN
  -- user-service: users 스키마 DML
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'user_service') THEN
    GRANT USAGE ON SCHEMA users TO user_service;
    GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA users TO user_service;
    GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA users TO user_service;
  END IF;

  -- point-command: 원장 쓰기. point_ledger 는 SELECT·INSERT 만 → append-only 를 DB 권한으로 강제 (공통 규칙 §4)
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'point_command') THEN
    GRANT USAGE ON SCHEMA point TO point_command;
    GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA point TO point_command;
    REVOKE UPDATE, DELETE, TRUNCATE ON point.point_ledger FROM point_command;
    GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA point TO point_command;
  END IF;

  -- point-query: 조회 전용
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'point_query') THEN
    GRANT USAGE ON SCHEMA point TO point_query;
    GRANT SELECT ON ALL TABLES IN SCHEMA point TO point_query;
  END IF;

  -- 이행 기간: core-service 가 쓰는 app_rw. point_migrator 가 새로 만든 테이블에도 접근해야 한다.
  -- core-service → user-service 전환(point-infra#60)에서 app_rw 와 함께 이 블록을 지운다
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rw') AND current_user <> 'app_rw' THEN
    GRANT USAGE ON SCHEMA users, asset, point, cs TO app_rw;
    GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA users, asset, point, cs TO app_rw;
    GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA users, asset, point, cs TO app_rw;
  END IF;
END
$$;
