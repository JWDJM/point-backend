package com.pointplatform.monolith.point.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pointplatform.monolith.point.application.PointService;
import com.pointplatform.monolith.point.application.RewardSettlementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * event-service 가 발행한 point.reward.requested 를 소비한다.
 * - 출석 보상 등: 원장에 즉시 멱등 반영
 * - 퀴즈 당첨(reason=QUIZ_REWARD): 원장은 건드리지 않고 정산 대기열(reward_pending)에만 쌓는다.
 *   이벤트 종료 후 RewardSettlementService.settle 이 한 트랜잭션으로 일괄 지급한다 (point-infra ADR-0017)
 * DR(gcp-dr) 프로필에서는 Kafka 가 없으므로 비활성화된다. (app.kafka.enabled=false)
 * 실패 시 재시도 후 point.reward.dlq 로 이동한다 → RewardConsumerErrorConfig
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class RewardEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(RewardEventConsumer.class);

    /** event-service RewardPublisher.publishQuizReward 의 reason */
    static final String QUIZ_REWARD = "QUIZ_REWARD";
    /** point_ledger / reward_pending 의 idempotency_key, reference_id 컬럼 길이 */
    private static final int MAX_KEY_LENGTH = 100;

    private final PointService pointService;
    private final RewardSettlementService settlementService;
    private final ObjectMapper objectMapper;

    public RewardEventConsumer(PointService pointService, RewardSettlementService settlementService,
                               ObjectMapper objectMapper) {
        this.pointService = pointService;
        this.settlementService = settlementService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "point.reward.requested", groupId = "point-ledger")
    public void consume(String payload) throws Exception {
        RewardRequested event = objectMapper.readValue(payload, RewardRequested.class);
        // 비정상 메시지는 원장을 오염시키지 않고 재시도 없이 DLQ 로 (IllegalArgumentException = 비재시도, RewardConsumerErrorConfig)
        if (event == null || event.userId() <= 0 || event.amount() <= 0
                || event.idempotencyKey() == null || event.idempotencyKey().isBlank()
                || event.eventId() == null || event.eventId().isBlank()
                || event.idempotencyKey().length() > MAX_KEY_LENGTH || event.eventId().length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("Invalid reward event: " + event);
        }
        if (QUIZ_REWARD.equals(event.reason())) {
            boolean staged = settlementService.stage(event.idempotencyKey(), event.userId(), event.eventId(), event.amount());
            log.info("reward staged for settlement key={} user={} amount={} new={}",
                    event.idempotencyKey(), event.userId(), event.amount(), staged);
            return;
        }
        long balance = pointService.earn(event.userId(), event.amount(), event.idempotencyKey(),
                "EVENT", event.eventId());
        log.info("reward applied key={} user={} amount={} balance={}",
                event.idempotencyKey(), event.userId(), event.amount(), balance);
    }

    public record RewardRequested(String idempotencyKey, long userId, String eventId, long amount,
                           String reason, String occurredAt) {}
}
