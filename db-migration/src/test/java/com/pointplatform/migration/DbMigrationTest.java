package com.pointplatform.migration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/** 운영 프로필 기준: db/migration 만 적용되고 (시드 제외) 모든 스키마·테이블이 만들어진다 */
@SpringBootTest
@ActiveProfiles("aws")
@Testcontainers
class DbMigrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 운영_마이그레이션만_적용되고_시드는_없다() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM public.flyway_schema_history WHERE success", Integer.class);
        assertThat(applied).isEqualTo(3);   // db/migration 의 V 파일 수

        Integer users = jdbc.queryForObject("SELECT count(*) FROM users.app_user", Integer.class);
        assertThat(users).isZero();          // db/local 시드는 local 프로필에서만
    }

    @Test
    void 서비스가_쓰는_스키마와_테이블이_있다() {
        for (String table : new String[]{
                "users.app_user", "users.user_credential", "users.refresh_token",
                "point.point_wallet", "point.point_ledger", "point.refund_request",
                "asset.linked_account", "cs.notice", "public.dr_heartbeat"}) {
            Boolean exists = jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, table);
            assertThat(exists).as(table).isTrue();
        }
    }
}
