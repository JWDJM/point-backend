package com.pointplatform.pointquery.infra;

import com.pointplatform.pointquery.application.LedgerActivity;
import com.pointplatform.pointquery.application.PointStats;
import com.pointplatform.pointquery.domain.LedgerEntry;
import com.pointplatform.pointquery.domain.LedgerType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** point_wallet / point_ledger 조회 전용 (쿼리는 core-service PointRepository 의 조회와 같다) */
@Repository
public class PointQueryRepository {

    private final JdbcTemplate jdbc;

    public PointQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 지갑이 없으면 0 (지갑은 point-command 가 첫 적립·사용 때 만든다) */
    public long findBalance(long userId) {
        List<Long> rows = jdbc.queryForList(
                "SELECT balance FROM point.point_wallet WHERE user_id = ?", Long.class, userId);
        return rows.isEmpty() ? 0L : rows.get(0);
    }

    public List<LedgerEntry> findRecentLedger(long userId, int limit) {
        return jdbc.query("""
                SELECT id, type, amount, balance_after, source, reference_id, created_at
                FROM point.point_ledger WHERE user_id = ? ORDER BY created_at DESC, id DESC LIMIT ?
                """, (rs, i) -> new LedgerEntry(
                rs.getLong("id"),
                LedgerType.valueOf(rs.getString("type")),
                rs.getLong("amount"),
                rs.getLong("balance_after"),
                rs.getString("source"),
                rs.getString("reference_id"),
                rs.getObject("created_at", OffsetDateTime.class)), userId, limit);
    }

    // ---- 운영 대시보드용 집계 (/api/system/point-query/ledger) ----

    public long countWallets() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM point.point_wallet", Long.class);
        return n == null ? 0L : n;
    }

    public long sumBalances() {
        Long n = jdbc.queryForObject("SELECT COALESCE(sum(balance), 0) FROM point.point_wallet", Long.class);
        return n == null ? 0L : n;
    }

    public long countLedger() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM point.point_ledger", Long.class);
        return n == null ? 0L : n;
    }

    public List<PointStats.SourceSummary> summarizeBySource() {
        return jdbc.query("""
                SELECT source, type, count(*) AS cnt, COALESCE(sum(amount), 0) AS total
                FROM point.point_ledger GROUP BY source, type ORDER BY source, type
                """, (rs, i) -> new PointStats.SourceSummary(
                rs.getString("source"), rs.getString("type"), rs.getLong("cnt"), rs.getLong("total")));
    }

    public Optional<OffsetDateTime> findLastCreatedAtBySource(String source) {
        List<OffsetDateTime> rows = jdbc.query(
                "SELECT max(created_at) AS last_at FROM point.point_ledger WHERE source = ?",
                (rs, i) -> rs.getObject("last_at", OffsetDateTime.class), source);
        return rows.stream().filter(Objects::nonNull).findFirst();
    }

    /** 전체 사용자 최근 원장 (PK 역순 → 인덱스 사용) */
    public List<LedgerActivity> findRecentActivity(int limit) {
        return jdbc.query("""
                SELECT id, user_id, type, amount, balance_after, source, reference_id, created_at
                FROM point.point_ledger ORDER BY id DESC LIMIT ?
                """, (rs, i) -> new LedgerActivity(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("type"),
                rs.getLong("amount"),
                rs.getLong("balance_after"),
                rs.getString("source"),
                rs.getString("reference_id"),
                rs.getObject("created_at", OffsetDateTime.class)), limit);
    }
}
