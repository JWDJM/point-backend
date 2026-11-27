package com.pointplatform.pointquery;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 운영과 같은 조건: 스키마·시드·권한은 관리 계정으로 Flyway(db-migration SQL + afterMigrate) 적용,
 * 앱은 point_query 계정(point SELECT 만)으로 붙는다. 시드: 사용자 1 = 1,000P, 원장 1건.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("aws")
@Testcontainers
class PointQueryApiTest {

    private static final String ISSUER = "point-core";
    private static final KeyPair KEYS = rsa();

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16").withInitScript("test-app-roles.sql");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", () -> "point_query");
        r.add("spring.datasource.password", () -> "point_query_test");
        r.add("spring.flyway.enabled", () -> "true");   // 테스트에서만: 스키마 준비 (운영은 db-migration Job)
        r.add("spring.flyway.url", postgres::getJdbcUrl);
        r.add("spring.flyway.user", postgres::getUsername);
        r.add("spring.flyway.password", postgres::getPassword);
        r.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/local");
        r.add("app.auth.public-key", () -> publicPem(KEYS));
        r.add("app.auth.issuer", () -> ISSUER);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 잔액() throws Exception {
        mvc.perform(get("/api/points/balance").header("Authorization", bearer("1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.balance").value(1000));
    }

    @Test
    void 지갑이_없으면_잔액_0() throws Exception {
        mvc.perform(get("/api/points/balance").header("Authorization", bearer("999")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(0));
    }

    @Test
    void 내역_응답_형식은_core_service_와_같다() throws Exception {
        mvc.perform(get("/api/points/history").header("Authorization", bearer("1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].type").value("EARN"))
                .andExpect(jsonPath("$.data[0].amount").value(1000))
                .andExpect(jsonPath("$.data[0].balanceAfter").value(1000))
                .andExpect(jsonPath("$.data[0].source").value("ADMIN"))
                .andExpect(jsonPath("$.data[0].referenceId").value("local-seed"))
                .andExpect(jsonPath("$.data[0].id").exists())
                .andExpect(jsonPath("$.data[0].createdAt").exists());
    }

    @Test
    void 토큰이_없거나_발급자가_다르면_401() throws Exception {
        mvc.perform(get("/api/points/balance"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/points/balance").header("Authorization", "Bearer " + token("someone-else", "1")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void health_는_인증_없이_UP() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UP"));
    }

    @Test
    void 운영_API_는_서비스_접두어_경로() throws Exception {
        mvc.perform(get("/api/system/point-query/whoami"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.service").value("point-query"));
        mvc.perform(get("/api/system/point-query/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.database.status").value("UP"));
        mvc.perform(get("/api/system/point-query/ledger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.walletCount").value(1))
                .andExpect(jsonPath("$.data.ledgerCount").value(1));
    }

    @Test
    void 앱_계정으로는_쓸_수_없다() {
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("point_query");
        assertThat(jdbc.queryForObject(
                "SELECT has_table_privilege('point.point_ledger', 'INSERT') OR has_table_privilege('point.point_wallet', 'UPDATE')",
                Boolean.class)).isFalse();

        // 커넥션이 read-only(25006)라 권한 검사 전에 막히고, 권한(42501)도 없다
        assertThatThrownBy(() -> jdbc.update("UPDATE point.point_wallet SET balance = 0 WHERE user_id = 1"))
                .isInstanceOf(DataAccessException.class)
                .rootCause().isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isIn("25006", "42501"));
    }

    private static String bearer(String sub) {
        return "Bearer " + token(ISSUER, sub);
    }

    private static String token(String issuer, String sub) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), new JWTClaimsSet.Builder()
                    .issuer(issuer).subject(sub).issueTime(new Date())
                    .expirationTime(new Date(System.currentTimeMillis() + 60_000)).build());
            jwt.sign(new RSASSASigner((RSAPrivateKey) KEYS.getPrivate()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String publicPem(KeyPair keys) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keys.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }

    private static KeyPair rsa() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
