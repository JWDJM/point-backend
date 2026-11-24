package com.pointplatform.monolith.point;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pointplatform.monolith.point.application.PointService;
import com.pointplatform.monolith.point.infra.RewardEventConsumer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 보상 메시지: 퀴즈 당첨·출석 모두 원장에 즉시 멱등 반영 (ADR-0018) */
class RewardEventConsumerTest {

    private final PointService pointService = mock(PointService.class);
    private final RewardEventConsumer consumer = new RewardEventConsumer(pointService, new ObjectMapper());

    @Test
    void 퀴즈_당첨은_원장에_즉시_반영한다() throws Exception {
        consumer.consume("""
                {"idempotencyKey":"evt-1:7","userId":7,"eventId":"evt-1","amount":500,"reason":"QUIZ_REWARD"}""");

        verify(pointService).earn(7, 500, "evt-1:7", "EVENT", "evt-1");
    }

    @Test
    void 출석_보상은_원장에_즉시_반영한다() throws Exception {
        consumer.consume("""
                {"idempotencyKey":"attendance-20261117:7","userId":7,"eventId":"attendance-20261117",
                 "amount":10,"reason":"ATTENDANCE_REWARD"}""");

        verify(pointService).earn(7, 10, "attendance-20261117:7", "EVENT", "attendance-20261117");
    }

    @Test
    void 키가_컬럼_길이를_넘는_메시지는_반영하지_않는다() {
        assertThatThrownBy(() -> consumer.consume("""
                {"idempotencyKey":"%s","userId":7,"eventId":"evt-1","amount":500,"reason":"QUIZ_REWARD"}"""
                .formatted("k".repeat(101))))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(pointService);
    }
}
