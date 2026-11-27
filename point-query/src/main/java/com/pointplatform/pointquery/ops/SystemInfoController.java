package com.pointplatform.pointquery.ops;

import com.pointplatform.common.error.ApiResponse;
import com.pointplatform.common.ops.ComponentStatus;
import com.pointplatform.common.ops.InstanceInfo;
import com.pointplatform.common.ops.Probes;
import com.pointplatform.pointquery.application.PointQueryService;
import com.pointplatform.pointquery.application.PointStats;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Arrays;

/**
 * 운영/인프라 검증용 읽기 전용 API — 경로 형식 /api/system/{서비스}/** (ADR-0024).
 * - /whoami : 응답한 Pod (부하 분산 확인)
 * - /info   : 인스턴스 + DB 상태 (point-query 는 Redis 를 쓰지 않는다)
 * - /ledger : 원장 집계 + 전체 사용자 최근 활동
 * 내부 정보를 노출하므로 운영에서 끄려면 OPS_API_ENABLED=false.
 */
@RestController
@RequestMapping("/api/system/point-query")
@ConditionalOnProperty(name = "app.ops.enabled", havingValue = "true", matchIfMissing = true)
public class SystemInfoController {

    static final String SERVICE = "point-query";
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);

    private final DatabaseProbe databaseProbe;
    private final PointQueryService pointQueryService;
    private final Environment environment;
    private final boolean drMode;

    public SystemInfoController(DatabaseProbe databaseProbe, PointQueryService pointQueryService, Environment environment,
                                @Value("${app.dr-mode:false}") boolean drMode) {
        this.databaseProbe = databaseProbe;
        this.pointQueryService = pointQueryService;
        this.environment = environment;
        this.drMode = drMode;
    }

    @GetMapping("/whoami")
    public ApiResponse<InstanceInfo> whoami() {
        return ApiResponse.ok(instance());
    }

    @GetMapping("/info")
    public ApiResponse<SystemInfo> info() {
        return ApiResponse.ok(new SystemInfo(instance(), Probes.run(PROBE_TIMEOUT, databaseProbe::check)));
    }

    @GetMapping("/ledger")
    public ApiResponse<PointStats> ledger(@RequestParam(defaultValue = "15") int recent) {
        return ApiResponse.ok(pointQueryService.getStats(recent));
    }

    private InstanceInfo instance() {
        String[] profiles = environment.getActiveProfiles().length > 0
                ? environment.getActiveProfiles() : environment.getDefaultProfiles();
        // rewardStreamEnabled: point-query 는 Stream 을 읽지 않는다 (InstanceInfo 형식은 다른 서비스와 같게 둔다)
        return InstanceInfo.current(SERVICE, Arrays.asList(profiles), drMode, false);
    }

    public record SystemInfo(InstanceInfo instance, ComponentStatus database) {}
}
