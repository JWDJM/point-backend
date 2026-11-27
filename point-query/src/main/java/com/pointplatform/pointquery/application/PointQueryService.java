package com.pointplatform.pointquery.application;

import com.pointplatform.pointquery.domain.LedgerEntry;
import com.pointplatform.pointquery.infra.PointQueryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 포인트 조회 유스케이스 (모두 읽기 전용) */
@Service
@Transactional(readOnly = true)
public class PointQueryService {

    static final int HISTORY_LIMIT = 20;
    static final int MAX_RECENT = 50;

    private final PointQueryRepository repository;

    public PointQueryService(PointQueryRepository repository) {
        this.repository = repository;
    }

    public long getBalance(long userId) {
        return repository.findBalance(userId);
    }

    public List<LedgerEntry> getHistory(long userId) {
        return repository.findRecentLedger(userId, HISTORY_LIMIT);
    }

    /** 운영 대시보드용 원장 집계 + 전체 사용자 최근 활동. lastEventRewardAt 으로 Redis Stream 반영이 도는지 본다 */
    public PointStats getStats(int recentLimit) {
        return new PointStats(
                repository.countWallets(),
                repository.sumBalances(),
                repository.countLedger(),
                repository.summarizeBySource(),
                repository.findLastCreatedAtBySource("EVENT").orElse(null),
                repository.findRecentActivity(Math.max(1, Math.min(recentLimit, MAX_RECENT))));
    }
}
