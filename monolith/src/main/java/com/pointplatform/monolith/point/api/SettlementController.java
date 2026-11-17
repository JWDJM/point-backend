package com.pointplatform.monolith.point.api;

import com.pointplatform.monolith.common.error.ApiResponse;
import com.pointplatform.monolith.common.security.AdminApiGuard;
import com.pointplatform.monolith.point.application.RewardSettlementService;
import com.pointplatform.monolith.point.application.SettlementResult;
import com.pointplatform.monolith.point.application.SettlementStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.web.bind.annotation.*;

/**
 * 퀴즈 이벤트 일괄 정산 (운영 Job·DR 런북 전용, X-Admin-Token).
 * <pre>
 * GET  /api/admin/events/{eventId}/settlement            현황 (NOT_SETTLED / COMPLETE / INCOMPLETE)
 * POST /api/admin/events/{eventId}/settlement            정산 {"expectedCount": Redis 당첨자 수}
 *                                                        이미 정산된 이벤트면 본문 없이 호출 → 누락분만 채움
 * </pre>
 */
@RestController
@RequestMapping("/api/admin/events/{eventId}/settlement")
public class SettlementController {

    private final RewardSettlementService settlementService;
    private final AdminApiGuard adminApiGuard;

    public SettlementController(RewardSettlementService settlementService, AdminApiGuard adminApiGuard) {
        this.settlementService = settlementService;
        this.adminApiGuard = adminApiGuard;
    }

    @GetMapping
    public ApiResponse<SettlementStatus> status(@PathVariable String eventId,
                                                @RequestHeader(value = AdminApiGuard.HEADER, required = false) String token) {
        adminApiGuard.check(token);
        return ApiResponse.ok(settlementService.status(eventId));
    }

    @PostMapping
    public ApiResponse<SettlementResult> settle(@PathVariable String eventId,
                                                @RequestHeader(value = AdminApiGuard.HEADER, required = false) String token,
                                                @Valid @RequestBody(required = false) SettleRequest request) {
        adminApiGuard.check(token);
        Long expected = request == null ? null : request.expectedCount();
        return ApiResponse.ok(settlementService.settle(eventId, expected));
    }

    /** @param expectedCount 최초 정산 시 필수: event-service 의 당첨자 수 (Redis SCARD event:{id}:winners) */
    public record SettleRequest(@PositiveOrZero Long expectedCount) {}
}
