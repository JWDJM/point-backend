package com.pointplatform.pointcommand;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 운영과 같은 조건: 스키마·시드·권한은 관리 계정으로 Flyway(+afterMigrate), 앱은 point_command 계정으로 붙는다.
 * Redis 는 쓰지 않는 조건(DR 과 같음 — REWARD_STREAM_ENABLED=false)으로 띄운다. 시드: 사용자 1 = 1,000P.
 */
@SpringBootTest(properties = {"app.reward-stream.enabled=false", "app.dr-mode=true", "spring.data.redis.port=1"})
@AutoConfigureMockMvc
@ActiveProfiles("aws")
@Testcontainers
class PointCommandApiTest {

    private static final String ISSUER = "point-core";
    private static final KeyPair KEYS = rsa();

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16").withInitScript("test-app-roles.sql");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", () -> "point_command");
        r.add("spring.datasource.password", () -> "point_command_test");
        r.add("spring.flyway.url", postgres::getJdbcUrl);
        r.add("spring.flyway.user", postgres::getUsername);
        r.add("spring.flyway.password", postgres::getPassword);
        r.add("app.auth.public-key", () -> publicPem(KEYS));
        r.add("app.auth.issuer", () -> ISSUER);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void 포인트_사용은_잔액을_차감하고_같은_키로_다시_오면_한_번만_처리한다() throws Exception {
        use("2", "pay-none", 100).andExpect(status().isConflict());   // 사용자 2 는 지갑·잔액 0
        use("1", "pay-1", 100).andExpect(status().isOk()).andExpect(jsonPath("$.data.balance").value(900));
        use("1", "pay-1", 100).andExpect(status().isOk()).andExpect(jsonPath("$.data.balance").value(900));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM point.point_ledger WHERE idempotency_key = 'use:1:pay-1'", Integer.class)).isOne();
    }

    @Test
    void 잔액보다_많이_쓰면_409_INSUFFICIENT_BALANCE() throws Exception {
        use("3", "too-much", 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));
    }

    @Test
    void 멱등키가_없으면_400_토큰이_없으면_401() throws Exception {
        mvc.perform(post("/api/points/use").header("Authorization", bearer("1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1,\"merchantRef\":\"m\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/points/use").header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1,\"merchantRef\":\"m\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 원장은_앱_계정으로도_수정_삭제할_수_없다() {
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("point_command");
        for (String sql : new String[]{"UPDATE point.point_ledger SET amount = 0", "DELETE FROM point.point_ledger"}) {
            assertThatThrownBy(() -> jdbc.update(sql))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause().isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42501"));
        }
    }

    @Test
    void Redis_없이_기동하고_운영_API_는_Stream_을_DISABLED_로_보여준다() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/api/system/point-command/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.instance.service").value("point-command"))
                .andExpect(jsonPath("$.data.database.status").value("UP"))
                .andExpect(jsonPath("$.data.rewardStream.status").value("DISABLED"));
    }

    private ResultActions use(String sub, String key, long amount) throws Exception {
        return mvc.perform(post("/api/points/use").header("Authorization", bearer(sub)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":" + amount + ",\"merchantRef\":\"api-test\"}"));
    }

    private static String bearer(String sub) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), new JWTClaimsSet.Builder()
                    .issuer(ISSUER).subject(sub).issueTime(new Date())
                    .expirationTime(new Date(System.currentTimeMillis() + 60_000)).build());
            jwt.sign(new RSASSASigner((RSAPrivateKey) KEYS.getPrivate()));
            return "Bearer " + jwt.serialize();
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
