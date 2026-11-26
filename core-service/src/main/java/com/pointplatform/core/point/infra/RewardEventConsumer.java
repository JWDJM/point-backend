package com.pointplatform.core.point.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pointplatform.core.point.application.PointService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * event-service 가 발행한 point.reward.requested 를 소비해 원장에 멱등 반영한다.
 * 퀴즈 당첨(QUIZ_REWARD)·출석 보상(ATTENDANCE_REWARD) 모두 즉시 지급한다
 * (이벤트 종료 후 일괄 정산 방식은 point-infra ADR-0018 로 철회 — ADR-0017 참고).
 * DR(gcp-dr) 프로필에서는 Kafka 가 없으므로 비활성화된다. (app.kafka.enabled=false)
 * 실패 시 재시도 후 point.reward.dlq 로 이동한다 → RewardConsumerErrorConfig
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class RewardEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(RewardEventConsumer.class);

    /** point_ledger 의 idempotency_key, reference_id 컬럼 길이 */
    private static final int MAX_KEY_LENGTH = 100;

    private final PointService pointService;
    private final ObjectMapper objectMapper;

    public RewardEventConsumer(PointService pointService, ObjectMapper objectMapper) {
        this.pointService = pointService;
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
        long balance = pointService.earn(event.userId(), event.amount(), event.idempotencyKey(),
                "EVENT", event.eventId());
        log.info("reward applied key={} user={} amount={} reason={} balance={}",
                event.idempotencyKey(), event.userId(), event.amount(), event.reason(), balance);
    }

    public record RewardRequested(String idempotencyKey, long userId, String eventId, long amount,
                           String reason, String occurredAt) {}
}
