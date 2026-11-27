package com.pointplatform.pointcommand.ops;

import com.pointplatform.common.error.ApiResponse;
import com.pointplatform.common.ops.ComponentStatus;
import com.pointplatform.common.ops.InstanceInfo;
import com.pointplatform.common.ops.Probes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Arrays;

/**
 * 운영/인프라 검증용 읽기 전용 API — 경로 형식 /api/system/{서비스}/** (ADR-0024).
 * - /whoami : 응답한 Pod (부하 분산 확인)
 * - /info   : 인스턴스 + DB + 보상 Stream 소비 상태 (pending·DLQ)
 * 내부 정보를 노출하므로 운영에서 끄려면 OPS_API_ENABLED=false.
 */
@RestController
@RequestMapping("/api/system/point-command")
@ConditionalOnProperty(name = "app.ops.enabled", havingValue = "true", matchIfMissing = true)
public class SystemInfoController {

    static final String SERVICE = "point-command";
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);

    private final DatabaseProbe databaseProbe;
    private final RewardStreamProbe rewardStreamProbe;
    private final Environment environment;
    private final boolean drMode;
    private final boolean rewardStreamEnabled;

    public SystemInfoController(DatabaseProbe databaseProbe, RewardStreamProbe rewardStreamProbe, Environment environment,
                                @Value("${app.dr-mode:false}") boolean drMode,
                                @Value("${app.reward-stream.enabled:true}") boolean rewardStreamEnabled) {
        this.databaseProbe = databaseProbe;
        this.rewardStreamProbe = rewardStreamProbe;
        this.environment = environment;
        this.drMode = drMode;
        this.rewardStreamEnabled = rewardStreamEnabled;
    }

    @GetMapping("/whoami")
    public ApiResponse<InstanceInfo> whoami() {
        return ApiResponse.ok(instance());
    }

    @GetMapping("/info")
    public ApiResponse<SystemInfo> info() {
        ComponentStatus database = Probes.run(PROBE_TIMEOUT, databaseProbe::check);
        ComponentStatus rewardStream = rewardStreamEnabled
                ? Probes.run(PROBE_TIMEOUT, rewardStreamProbe::check)
                : ComponentStatus.disabled("REWARD_STREAM_ENABLED=false (DR 모드에는 Redis 가 없어 원장 반영 consumer 비활성)");
        return ApiResponse.ok(new SystemInfo(instance(), database, rewardStream));
    }

    private InstanceInfo instance() {
        String[] profiles = environment.getActiveProfiles().length > 0
                ? environment.getActiveProfiles() : environment.getDefaultProfiles();
        return InstanceInfo.current(SERVICE, Arrays.asList(profiles), drMode, rewardStreamEnabled);
    }

    /** core-service 의 /api/system/info 와 같은 형식 (instance, database, rewardStream) */
    public record SystemInfo(InstanceInfo instance, ComponentStatus database, ComponentStatus rewardStream) {}
}
