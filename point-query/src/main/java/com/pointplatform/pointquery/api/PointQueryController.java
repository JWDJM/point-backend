package com.pointplatform.pointquery.api;

import com.pointplatform.common.error.ApiResponse;
import com.pointplatform.common.security.LoginUser;
import com.pointplatform.pointquery.application.PointQueryService;
import com.pointplatform.pointquery.domain.LedgerEntry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 잔액·내역 조회. 사용자는 JWT(sub) 로 식별한다 (@LoginUser). 경로·응답은 core-service 와 같다 (api/openapi.yaml) */
@RestController
@RequestMapping("/api/points")
public class PointQueryController {

    private final PointQueryService service;

    public PointQueryController(PointQueryService service) {
        this.service = service;
    }

    @GetMapping("/balance")
    public ApiResponse<BalanceResponse> balance(@LoginUser long userId) {
        return ApiResponse.ok(new BalanceResponse(service.getBalance(userId)));
    }

    @GetMapping("/history")
    public ApiResponse<List<LedgerEntry>> history(@LoginUser long userId) {
        return ApiResponse.ok(service.getHistory(userId));
    }

    public record BalanceResponse(long balance) {}
}
