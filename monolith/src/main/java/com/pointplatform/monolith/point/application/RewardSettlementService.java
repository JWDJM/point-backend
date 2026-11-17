package com.pointplatform.monolith.point.application;

import com.pointplatform.monolith.common.error.BusinessException;
import com.pointplatform.monolith.common.error.ErrorCode;
import com.pointplatform.monolith.point.infra.SettlementRepository;
import com.pointplatform.monolith.point.infra.SettlementRepository.Applied;
import com.pointplatform.monolith.point.infra.SettlementRepository.PendingSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 퀴즈 이벤트 일괄 정산 (point-infra ADR-0017).
 * <ol>
 *   <li>이벤트 중: Kafka 당첨 메시지를 reward_pending 에 쌓기만 한다 ({@link #stage}) — 원장·지갑은 그대로</li>
 *   <li>이벤트 종료 후: 한 트랜잭션으로 대기 건 전부를 원장·지갑에 반영하고 정산 기록을 남긴다 ({@link #settle})</li>
 * </ol>
 * 원본 DB 에서는 이벤트 단위로 "전부 지급 / 전혀 지급 안 됨" 만 존재한다. CDC 로 복제된 DR 측에서는
 * 정산 기록(row_count)과 원장 건수를 비교해 완료 여부를 판정하고, 모자라면 settle 을 다시 호출해 채운다(멱등).
 */
@Service
public class RewardSettlementService {

    private static final Logger log = LoggerFactory.getLogger(RewardSettlementService.class);
    static final int MAX_EVENT_ID_LENGTH = 100;

    private final SettlementRepository repository;

    public RewardSettlementService(SettlementRepository repository) {
        this.repository = repository;
    }

    /** 당첨 1건을 정산 대기열에 쌓는다. @return 새로 쌓였으면 true (같은 메시지 재전달이면 false) */
    @Transactional
    public boolean stage(String idempotencyKey, long userId, String eventId, long amount) {
        boolean inserted = repository.insertPending(idempotencyKey, eventId, userId, amount);
        if (inserted && repository.findSettlement(eventId).isPresent()) {
            // 정산 이후 도착한 당첨 (정산 전제조건 위반). 다음 settle 호출이 누락분으로 지급한다
            log.warn("reward staged after settlement event={} user={} key={}", eventId, userId, idempotencyKey);
        }
        return inserted;
    }

    /**
     * 이벤트 정산.
     * <ul>
     *   <li>최초 정산: 대기 건수가 expectedCount(= Redis 당첨자 수)와 같아야 한다. 다르면 아직 Kafka 에서
     *       반영 중인 당첨이 있다는 뜻이므로 409 SETTLEMENT_NOT_READY (원장은 건드리지 않음)</li>
     *   <li>이미 정산된 이벤트: expectedCount 는 무시하고 원장에 없는 대기 건만 채운다 (DR 재정산, 재실행)</li>
     * </ul>
     */
    @Transactional
    public SettlementResult settle(String eventId, Long expectedCount) {
        validateEventId(eventId);
        repository.lockEvent(eventId);

        Optional<EventSettlement> existing = repository.findSettlement(eventId);
        PendingSummary pending = repository.summarizePending(eventId);
        if (existing.isEmpty()) {
            if (expectedCount == null || expectedCount < 0) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
            if (pending.count() != expectedCount) {
                log.info("settlement not ready event={} pending={} expected={}", eventId, pending.count(), expectedCount);
                throw new BusinessException(ErrorCode.SETTLEMENT_NOT_READY);
            }
        }

        repository.ensureWallets(eventId);
        repository.lockWallets(eventId);
        var applied = repository.insertLedgerFromPending(eventId);
        repository.addToWallets(applied);

        if (existing.isEmpty()) {
            repository.insertSettlement(eventId, pending.count(), pending.totalAmount());
        }
        EventSettlement settlement = existing.orElseGet(() -> repository.findSettlement(eventId).orElseThrow());
        long appliedAmount = applied.stream().mapToLong(Applied::amount).sum();
        log.info("settlement event={} rows={} total={} appliedNow={} appliedAmount={} repaired={}",
                eventId, settlement.rowCount(), settlement.totalAmount(), applied.size(), appliedAmount, existing.isPresent());
        return new SettlementResult(eventId, settlement.rowCount(), settlement.totalAmount(), applied.size(),
                existing.isPresent(), settlement.settledAt());
    }

    @Transactional(readOnly = true)
    public SettlementStatus status(String eventId) {
        validateEventId(eventId);
        Optional<EventSettlement> settlement = repository.findSettlement(eventId);
        PendingSummary pending = repository.summarizePending(eventId);
        long ledger = repository.countLedger(eventId);
        long unapplied = repository.countUnapplied(eventId);

        SettlementStatus.State state = settlement.isEmpty() ? SettlementStatus.State.NOT_SETTLED
                : ledger >= settlement.get().rowCount() ? SettlementStatus.State.COMPLETE
                : SettlementStatus.State.INCOMPLETE;
        return new SettlementStatus(eventId, state, pending.count(), pending.totalAmount(), ledger, unapplied,
                settlement.map(EventSettlement::rowCount).orElse(null),
                settlement.map(EventSettlement::totalAmount).orElse(null),
                settlement.map(EventSettlement::settledAt).orElse(null));
    }

    private static void validateEventId(String eventId) {
        if (eventId == null || eventId.isBlank() || eventId.length() > MAX_EVENT_ID_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }
}
