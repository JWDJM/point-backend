package com.pointplatform.monolith.point;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pointplatform.monolith.point.application.PointService;
import com.pointplatform.monolith.point.application.RewardSettlementService;
import com.pointplatform.monolith.point.infra.RewardEventConsumer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 보상 메시지 분기: 퀴즈 당첨은 정산 대기열로, 그 외(출석 등)는 원장에 즉시 반영 */
class RewardEventConsumerTest {

    private final PointService pointService = mock(PointService.class);
    private final RewardSettlementService settlementService = mock(RewardSettlementService.class);
    private final RewardEventConsumer consumer =
            new RewardEventConsumer(pointService, settlementService, new ObjectMapper());

    @Test
    void 퀴즈_당첨은_원장에_바로_넣지_않고_정산_대기열에_쌓는다() throws Exception {
        consumer.consume("""
                {"idempotencyKey":"evt-1:7","userId":7,"eventId":"evt-1","amount":500,"reason":"QUIZ_REWARD"}""");

        verify(settlementService).stage("evt-1:7", 7, "evt-1", 500);
        verify(pointService, never()).earn(anyLong(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void 출석_보상은_원장에_즉시_반영한다() throws Exception {
        consumer.consume("""
                {"idempotencyKey":"attendance-20261117:7","userId":7,"eventId":"attendance-20261117",
                 "amount":10,"reason":"ATTENDANCE_REWARD"}""");

        verify(pointService).earn(7, 10, "attendance-20261117:7", "EVENT", "attendance-20261117");
        verifyNoInteractions(settlementService);
    }

    @Test
    void 비정상_메시지는_어느_쪽에도_반영하지_않는다() {
        assertThatThrownBy(() -> consumer.consume("""
                {"idempotencyKey":"%s","userId":7,"eventId":"evt-1","amount":500,"reason":"QUIZ_REWARD"}"""
                .formatted("k".repeat(101))))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(settlementService, pointService);
    }
}
