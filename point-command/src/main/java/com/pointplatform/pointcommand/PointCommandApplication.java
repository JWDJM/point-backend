package com.pointplatform.pointcommand;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 포인트 쓰기 서비스 (point-infra ADR-0024): 포인트 사용 + 보상 Stream 적립. 원장에 쓰는 곳은 여기 한 곳뿐이다
 * (지갑 행 잠금·멱등키·음수 잔액 차단이 한 서비스 안에서만 동작해야 잔액이 꼬이지 않는다).
 * point_ledger 는 DB 권한상 INSERT·SELECT 만 가능하다 (append-only, db-migration afterMigrate.sql).
 */
@SpringBootApplication
public class PointCommandApplication {
    public static void main(String[] args) {
        SpringApplication.run(PointCommandApplication.class, args);
    }
}
