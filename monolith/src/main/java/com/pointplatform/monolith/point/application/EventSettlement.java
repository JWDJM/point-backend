package com.pointplatform.monolith.point.application;

import java.time.OffsetDateTime;

/** 정산 완료 기록 (point.event_settlement 한 행) */
public record EventSettlement(String eventId, long rowCount, long totalAmount, OffsetDateTime settledAt) {
}
