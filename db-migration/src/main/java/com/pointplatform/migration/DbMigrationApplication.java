package com.pointplatform.migration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Flyway 마이그레이션을 실행하고 종료한다 (point-infra ADR-0024).
 * <ul>
 *   <li>Spring Boot Flyway 자동 설정이 컨텍스트 시작 시 {@code migrate} 를 실행한다.</li>
 *   <li>마이그레이션이 실패하면 컨텍스트 시작이 실패해 종료 코드 1 → K8s Job 실패 → ArgoCD 가 서비스 sync 를 멈춘다.</li>
 *   <li>성공하면 종료 코드 0 으로 바로 끝난다 (웹 서버 없음).</li>
 * </ul>
 */
@SpringBootApplication
public class DbMigrationApplication {
    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(DbMigrationApplication.class, args)));
    }
}
