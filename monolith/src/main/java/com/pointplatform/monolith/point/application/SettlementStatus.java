package com.pointplatform.monolith.point.application;

import java.time.OffsetDateTime;

/**
 * 이벤트 정산 현황. DR 전환 후 판정에도 그대로 쓴다 (scripts/dr/check-event-settlement.sql 과 같은 기준).
 * <ul>
 *   <li>NOT_SETTLED — 정산 전. 원장에 이 이벤트 지급이 없다 (전원 미지급)</li>
 *   <li>COMPLETE    — 정산 완료, 원장 건수 = 정산 건수</li>
 *   <li>INCOMPLETE  — 정산 기록은 있는데 원장이 모자람 (복제 도중 끊김) → 대기 건이 다 있으면 재정산으로 완성</li>
 * </ul>
 * @param unappliedCount 원장에 아직 없는 대기 건 수
 */
public record SettlementStatus(String eventId, State state, long pendingCount, long pendingAmount,
                               long ledgerCount, long unappliedCount,
                               Long settledRowCount, Long settledTotalAmount, OffsetDateTime settledAt) {

    public enum State { NOT_SETTLED, COMPLETE, INCOMPLETE }
}
