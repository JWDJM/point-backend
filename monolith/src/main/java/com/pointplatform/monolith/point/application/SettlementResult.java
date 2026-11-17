package com.pointplatform.monolith.point.application;

import java.time.OffsetDateTime;

/**
 * 정산 실행 결과.
 * @param rowCount     정산 대상 당첨 건수 (최초 정산 시점에 확정, event_settlement.row_count)
 * @param totalAmount  정산 대상 총액
 * @param appliedNow   이번 호출에서 새로 원장에 들어간 건수 (재실행이면 0, DR 재정산이면 누락분 수)
 * @param repaired     이미 정산된 이벤트의 누락분을 채운 호출이면 true
 */
public record SettlementResult(String eventId, long rowCount, long totalAmount, long appliedNow,
                               boolean repaired, OffsetDateTime settledAt) {
}
