package com.pointplatform.monolith.point.infra;

import com.pointplatform.monolith.point.application.EventSettlement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 퀴즈 이벤트 일괄 정산용 테이블 접근 (reward_pending / event_settlement + 원장·지갑 일괄 반영).
 * 원장은 INSERT 만 한다. 모든 쓰기는 idempotency_key 기준 ON CONFLICT DO NOTHING 이라 재실행해도 안전하다.
 */
@Repository
public class SettlementRepository {

    private final JdbcTemplate jdbc;

    public SettlementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return 새로 쌓였으면 true, 같은 멱등키가 이미 있으면 false (Kafka 중복 전달) */
    public boolean insertPending(String idempotencyKey, String eventId, long userId, long amount) {
        return jdbc.update("""
                INSERT INTO point.reward_pending (idempotency_key, event_id, user_id, amount)
                VALUES (?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, idempotencyKey, eventId, userId, amount) == 1;
    }

    /** 같은 이벤트의 정산을 직렬화한다 (트랜잭션 종료 시 자동 해제) */
    public void lockEvent(String eventId) {
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(?))) l",
                Integer.class, "settlement:" + eventId);
    }

    public Optional<EventSettlement> findSettlement(String eventId) {
        return jdbc.query("""
                SELECT event_id, row_count, total_amount, settled_at
                FROM point.event_settlement WHERE event_id = ?
                """, (rs, i) -> new EventSettlement(
                rs.getString("event_id"),
                rs.getLong("row_count"),
                rs.getLong("total_amount"),
                rs.getObject("settled_at", OffsetDateTime.class)), eventId).stream().findFirst();
    }

    public PendingSummary summarizePending(String eventId) {
        return jdbc.queryForObject("""
                SELECT count(*) AS cnt, COALESCE(sum(amount), 0) AS total
                FROM point.reward_pending WHERE event_id = ?
                """, (rs, i) -> new PendingSummary(rs.getLong("cnt"), rs.getLong("total")), eventId);
    }

    /** 원장에 아직 없는 대기 건 수 (DR 측에서 재정산이 필요한지 판단) */
    public long countUnapplied(String eventId) {
        Long n = jdbc.queryForObject("""
                SELECT count(*) FROM point.reward_pending p
                WHERE p.event_id = ?
                  AND NOT EXISTS (SELECT 1 FROM point.point_ledger l WHERE l.idempotency_key = p.idempotency_key)
                """, Long.class, eventId);
        return n == null ? 0L : n;
    }

    /** 이 이벤트로 원장에 적립된 건수 */
    public long countLedger(String eventId) {
        Long n = jdbc.queryForObject("""
                SELECT count(*) FROM point.point_ledger
                WHERE source = 'EVENT' AND type = 'EARN' AND reference_id = ?
                """, Long.class, eventId);
        return n == null ? 0L : n;
    }

    /** 당첨자 지갑이 없으면 만든다 (잔액 0) */
    public void ensureWallets(String eventId) {
        jdbc.update("""
                INSERT INTO point.point_wallet (user_id, balance)
                SELECT DISTINCT user_id, 0 FROM point.reward_pending WHERE event_id = ?
                ON CONFLICT (user_id) DO NOTHING
                """, eventId);
    }

    /**
     * 당첨자 지갑 행 잠금. user_id 순서로 잠가서 동시에 도는 정산끼리 교착이 생기지 않게 한다
     * (결제·출석 반영은 지갑 1개만 잠그므로 순환 대기가 생기지 않는다).
     */
    public void lockWallets(String eventId) {
        jdbc.queryForList("""
                SELECT w.user_id FROM point.point_wallet w
                WHERE w.user_id IN (SELECT user_id FROM point.reward_pending WHERE event_id = ?)
                ORDER BY w.user_id
                FOR UPDATE
                """, Long.class, eventId);
    }

    /**
     * 원장에 없는 대기 건을 한 번에 원장으로 옮긴다. 지갑은 잠겨 있고 이벤트당 사용자 1건이므로
     * balance_after = 현재 잔액 + 금액 이 정확하다.
     * @return 이번에 새로 들어간 (userId, amount) 목록 → 같은 트랜잭션에서 지갑에 더한다
     */
    public List<Applied> insertLedgerFromPending(String eventId) {
        return jdbc.query("""
                INSERT INTO point.point_ledger
                  (user_id, type, amount, balance_after, idempotency_key, source, reference_id)
                SELECT p.user_id, 'EARN', p.amount, w.balance + p.amount, p.idempotency_key, 'EVENT', p.event_id
                FROM point.reward_pending p
                JOIN point.point_wallet w ON w.user_id = p.user_id
                WHERE p.event_id = ?
                  AND NOT EXISTS (SELECT 1 FROM point.point_ledger l WHERE l.idempotency_key = p.idempotency_key)
                ORDER BY p.user_id
                ON CONFLICT (idempotency_key) DO NOTHING
                RETURNING user_id, amount
                """, (rs, i) -> new Applied(rs.getLong("user_id"), rs.getLong("amount")), eventId);
    }

    public void addToWallets(List<Applied> applied) {
        if (applied.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                UPDATE point.point_wallet SET balance = balance + ?, version = version + 1, updated_at = now()
                WHERE user_id = ?
                """, applied, applied.size(), (ps, a) -> {
            ps.setLong(1, a.amount());
            ps.setLong(2, a.userId());
        });
    }

    public void insertSettlement(String eventId, long rowCount, long totalAmount) {
        jdbc.update("""
                INSERT INTO point.event_settlement (event_id, row_count, total_amount)
                VALUES (?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, eventId, rowCount, totalAmount);
    }

    public record PendingSummary(long count, long totalAmount) {}

    public record Applied(long userId, long amount) {}
}
