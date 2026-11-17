package com.pointplatform.monolith.point;

import com.pointplatform.monolith.common.error.BusinessException;
import com.pointplatform.monolith.common.error.ErrorCode;
import com.pointplatform.monolith.point.application.PointService;
import com.pointplatform.monolith.point.application.RewardSettlementService;
import com.pointplatform.monolith.point.application.SettlementResult;
import com.pointplatform.monolith.point.application.SettlementStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 퀴즈 이벤트 일괄 정산 (ADR-0017): 원자성·멱등성·DR 재정산 (실제 PostgreSQL) */
@SpringBootTest(properties = {"app.kafka.enabled=false", "app.admin.api-token=test-admin-token"})
@AutoConfigureMockMvc
@Testcontainers
class RewardSettlementTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Autowired RewardSettlementService settlement;
    @Autowired PointService pointService;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @Test
    void 정산하면_대기_건_전부가_한_번에_원장과_지갑에_반영된다() {
        String evt = "evt-all";
        pointService.earn(3001, 100, "test:seed:3001", "ADMIN", null);   // 기존 잔액이 있는 사용자
        stage(evt, 3001, 3002, 3003);

        assertThat(pointService.getBalance(3001)).isEqualTo(100);        // 정산 전에는 지급되지 않는다
        assertThat(ledgerCount(evt)).isZero();

        SettlementResult r = settlement.settle(evt, 3L);

        assertThat(r.rowCount()).isEqualTo(3);
        assertThat(r.totalAmount()).isEqualTo(1500);
        assertThat(r.appliedNow()).isEqualTo(3);
        assertThat(r.repaired()).isFalse();
        assertThat(pointService.getBalance(3001)).isEqualTo(600);
        assertThat(pointService.getBalance(3002)).isEqualTo(500);
        assertThat(pointService.getBalance(3003)).isEqualTo(500);
        assertThat(balanceAfter("evt-all:3001")).isEqualTo(600);          // 원장 balance_after 도 정확해야 지갑 보정이 맞다
        assertThat(settlement.status(evt).state()).isEqualTo(SettlementStatus.State.COMPLETE);
    }

    @Test
    void 대기_건수가_당첨자_수와_다르면_아무것도_지급하지_않는다() {
        String evt = "evt-not-ready";
        stage(evt, 3101, 3102);   // 당첨 3명 중 1명은 아직 Kafka 에서 반영 중

        assertThat(errorCodeOf(() -> settlement.settle(evt, 3L))).isEqualTo(ErrorCode.SETTLEMENT_NOT_READY);
        assertThat(ledgerCount(evt)).isZero();
        assertThat(pointService.getBalance(3101)).isZero();
        assertThat(settlement.status(evt).state()).isEqualTo(SettlementStatus.State.NOT_SETTLED);
    }

    @Test
    void 최초_정산은_당첨자_수가_필요하다() {
        stage("evt-no-count", 3151);
        assertThat(errorCodeOf(() -> settlement.settle("evt-no-count", null))).isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void 같은_메시지가_중복_소비돼도_대기열에는_한_번만_쌓인다() {
        assertThat(settlement.stage("evt-dup:3201", 3201, "evt-dup", 500)).isTrue();
        assertThat(settlement.stage("evt-dup:3201", 3201, "evt-dup", 500)).isFalse();
        assertThat(settlement.status("evt-dup").pendingCount()).isEqualTo(1);
    }

    @Test
    void 정산을_다시_돌려도_중복_지급되지_않는다() {
        String evt = "evt-rerun";
        stage(evt, 3301, 3302);
        settlement.settle(evt, 2L);

        SettlementResult again = settlement.settle(evt, null);

        assertThat(again.appliedNow()).isZero();
        assertThat(again.repaired()).isTrue();
        assertThat(pointService.getBalance(3301)).isEqualTo(500);
        assertThat(ledgerCount(evt)).isEqualTo(2);
    }

    @Test
    void 동시에_여러_번_정산을_호출해도_한_번만_지급된다() throws Exception {
        String evt = "evt-concurrent";
        stage(evt, 3401, 3402, 3403, 3404);

        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return settlement.settle(evt, 4L).appliedNow();
                }));
            }
            start.countDown();
            long total = 0;
            for (Future<Long> f : futures) {
                total += f.get();
            }
            assertThat(total).isEqualTo(4);
        } finally {
            pool.shutdownNow();
        }
        assertThat(pointService.getBalance(3401)).isEqualTo(500);
        assertThat(ledgerCount(evt)).isEqualTo(4);
    }

    @Test
    void DR_측에서_원장이_일부만_복제됐으면_재정산으로_나머지를_채운다() {
        String evt = "evt-dr";
        stage(evt, 3501, 3502, 3503);
        settlement.settle(evt, 3L);

        // Cloud SQL 에 원장·지갑 중 3503 의 지급분만 복제되지 않은 상태를 재현 (정산 기록·대기 건은 복제됨)
        jdbc.update("DELETE FROM point.point_ledger WHERE idempotency_key = 'evt-dr:3503'");
        jdbc.update("UPDATE point.point_wallet SET balance = 0 WHERE user_id = 3503");

        SettlementStatus before = settlement.status(evt);
        assertThat(before.state()).isEqualTo(SettlementStatus.State.INCOMPLETE);
        assertThat(before.unappliedCount()).isEqualTo(1);

        SettlementResult repaired = settlement.settle(evt, null);

        assertThat(repaired.repaired()).isTrue();
        assertThat(repaired.appliedNow()).isEqualTo(1);
        assertThat(pointService.getBalance(3503)).isEqualTo(500);
        assertThat(pointService.getBalance(3501)).isEqualTo(500);   // 이미 받은 사람은 그대로
        assertThat(settlement.status(evt).state()).isEqualTo(SettlementStatus.State.COMPLETE);
    }

    @Test
    void 관리자_API_는_토큰이_없으면_403_있으면_정산한다() throws Exception {
        stage("evt-api", 3601);

        mvc.perform(post("/api/admin/events/evt-api/settlement").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedCount\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post("/api/admin/events/evt-api/settlement").header("X-Admin-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedCount\":1}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/admin/events/evt-api/settlement").header("X-Admin-Token", "test-admin-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedCount\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SETTLEMENT_NOT_READY"));
        mvc.perform(post("/api/admin/events/evt-api/settlement").header("X-Admin-Token", "test-admin-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedCount\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appliedNow").value(1));
        mvc.perform(get("/api/admin/events/evt-api/settlement").header("X-Admin-Token", "test-admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("COMPLETE"))
                .andExpect(jsonPath("$.data.ledgerCount").value(1));
    }

    private static ErrorCode errorCodeOf(Runnable call) {
        try {
            call.run();
        } catch (BusinessException e) {
            return e.errorCode();
        }
        throw new AssertionError("BusinessException expected");
    }

    private void stage(String eventId, long... users) {
        for (long u : users) {
            settlement.stage(eventId + ":" + u, u, eventId, 500);
        }
    }

    private long ledgerCount(String eventId) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM point.point_ledger WHERE source = 'EVENT' AND reference_id = ?", Long.class, eventId);
        return n == null ? 0 : n;
    }

    private long balanceAfter(String key) {
        Long n = jdbc.queryForObject(
                "SELECT balance_after FROM point.point_ledger WHERE idempotency_key = ?", Long.class, key);
        return n == null ? -1 : n;
    }
}
