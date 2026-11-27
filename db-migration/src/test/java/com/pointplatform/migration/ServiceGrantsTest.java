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

import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * afterMigrate.sql 이 서비스 계정에 건 권한 (ADR-0024). 계정은 운영처럼 마이그레이션 전에 있다.
 * 각 검사는 트랜잭션 안에서 SET LOCAL ROLE 로 그 계정이 되어 실행하고 롤백한다.
 */
@SpringBootTest
@ActiveProfiles("aws")
@Testcontainers
class ServiceGrantsTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16").withInitScript("test-app-roles.sql");

    @Autowired
    JdbcTemplate jdbc;

    private static final String INSERT_LEDGER = """
            INSERT INTO point.point_ledger (user_id, type, amount, balance_after, idempotency_key, source)
            VALUES (1, 'EARN', 100, 100, 'grants-test-' || gen_random_uuid(), 'TEST')""";

    @Test
    void point_command_는_원장을_추가만_할_수_있다() {
        runAs("point_command", INSERT_LEDGER);
        runAs("point_command", "INSERT INTO point.point_wallet (user_id, balance) VALUES (1, 0)");
        runAs("point_command", "SELECT balance FROM point.point_wallet WHERE user_id = 1 FOR UPDATE");

        assertDenied("point_command", "UPDATE point.point_ledger SET amount = 0");
        assertDenied("point_command", "DELETE FROM point.point_ledger");
        assertDenied("point_command", "SELECT 1 FROM users.app_user");
    }

    @Test
    void point_query_는_조회만_할_수_있다() {
        runAs("point_query", "SELECT count(*) FROM point.point_ledger");
        runAs("point_query", "SELECT count(*) FROM point.point_wallet");

        assertDenied("point_query", INSERT_LEDGER);
        assertDenied("point_query", "UPDATE point.point_wallet SET balance = 0");
        assertDenied("point_query", "SELECT 1 FROM users.app_user");
    }

    @Test
    void user_service_는_users_스키마만_쓸_수_있다() {
        runAs("user_service", "INSERT INTO users.app_user (email, name_enc) VALUES ('grants@test', 'x')");
        runAs("user_service", "DELETE FROM users.refresh_token");

        assertDenied("user_service", "SELECT 1 FROM point.point_wallet");
    }

    @Test
    void 서비스_계정은_DR_heartbeat_를_조회만_할_수_있다() {
        for (String role : new String[]{"user_service", "point_command", "point_query"}) {
            runAs(role, "SELECT ts FROM public.dr_heartbeat");
            assertDenied(role, "UPDATE public.dr_heartbeat SET ts = now()");
        }
    }

    @Test
    void 이행_기간의_app_rw_는_모든_도메인_스키마를_쓸_수_있다() {
        runAs("app_rw", INSERT_LEDGER);
        runAs("app_rw", "INSERT INTO users.app_user (email, name_enc) VALUES ('app_rw@test', 'x')");
    }

    @Test
    void 서비스_계정은_DDL_을_할_수_없다() {
        assertDenied("point_command", "CREATE TABLE point.nope (id int)");
        assertDenied("point_query", "CREATE TABLE point.nope (id int)");
        assertDenied("user_service", "ALTER TABLE users.app_user ADD COLUMN nope int");
    }

    private void runAs(String role, String sql) {
        jdbc.execute((java.sql.Connection c) -> {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SET LOCAL ROLE " + role);
                s.execute(sql);
            } finally {
                c.rollback();
                c.setAutoCommit(auto);
            }
            return null;
        });
    }

    private void assertDenied(String role, String sql) {
        assertThatThrownBy(() -> runAs(role, sql))
                .as(role + ": " + sql)
                .rootCause()
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState())
                        .as("42501 = insufficient_privilege")
                        .isIn("42501"));
    }
}
