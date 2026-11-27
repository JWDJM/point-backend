package com.pointplatform.pointcommand;

import com.pointplatform.pointcommand.infra.RewardMessageHandler;
import com.pointplatform.pointcommand.infra.RewardStreamConsumer;
import com.pointplatform.pointcommand.infra.RewardStreams;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전환 기간(ADR-0024): core-service 와 point-command 가 같은 consumer group(point-ledger)으로 동시에 읽는다.
 * 실제 Redis + PostgreSQL 위에서 두 consumer 를 나란히 돌려, 같은 보상(중복 메시지 포함)이 원장에 한 번만 반영되는지 본다.
 */
@SpringBootTest(properties = "app.reward-stream.enabled=false")   // 스케줄 consumer 는 끄고 테스트가 직접 돌린다
@Testcontainers
class SharedConsumerGroupTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redisContainer =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static final String STREAM = "event:{evt-shared}:rewards";
    private static final int USERS = 20;
    private static final long AMOUNT = 10;

    @Autowired StringRedisTemplate redis;
    @Autowired RewardStreams rewardStreams;
    @Autowired RewardMessageHandler handler;
    @Autowired JdbcTemplate jdbc;

    @Test
    void 두_consumer_가_같은_group_으로_동시에_읽어도_보상은_한_번만_반영된다() throws Exception {
        redis.opsForSet().add(RewardStreams.REGISTRY, STREAM);
        // 사용자마다 같은 보상을 2번씩 기록 (event-service 재시도로 생긴 중복과 같은 상황)
        for (int copy = 0; copy < 2; copy++) {
            for (int u = 1; u <= USERS; u++) {
                redis.opsForStream().add(StreamRecords.newRecord().in(STREAM).ofMap(Map.of(
                        "idempotencyKey", "quiz:evt-shared:" + (5000 + u),
                        "eventId", "evt-shared",
                        "userId", String.valueOf(5000 + u),
                        "amount", String.valueOf(AMOUNT),
                        "reason", "QUIZ_REWARD")));
            }
        }

        RewardStreamConsumer coreService = consumer("core-service-7d9f");
        RewardStreamConsumer pointCommand = consumer("point-command-5c2a");
        for (int round = 0; round < 3; round++) {
            CompletableFuture.allOf(
                    CompletableFuture.runAsync(coreService::poll),
                    CompletableFuture.runAsync(pointCommand::poll)).get();
        }

        Long ledgerRows = jdbc.queryForObject(
                "SELECT count(*) FROM point.point_ledger WHERE reference_id = 'evt-shared'", Long.class);
        Long total = jdbc.queryForObject(
                "SELECT COALESCE(sum(balance), 0) FROM point.point_wallet WHERE user_id BETWEEN 5001 AND 5020", Long.class);
        Long pending = redis.opsForStream().pending(STREAM, RewardStreams.GROUP).getTotalPendingMessages();

        assertThat(ledgerRows).isEqualTo(USERS);              // 메시지는 40건, 반영은 20건
        assertThat(total).isEqualTo(USERS * AMOUNT);          // 이중 적립 없음
        assertThat(pending).isZero();                         // 두 consumer 가 나눠 읽고 모두 XACK
    }

    private RewardStreamConsumer consumer(String name) {
        return new RewardStreamConsumer(redis, rewardStreams, handler, Clock.systemUTC(), name, Duration.ofSeconds(60), 5);
    }
}
