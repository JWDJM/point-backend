package com.pointplatform.pointquery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 포인트 조회 서비스 (point-infra ADR-0024): 잔액·내역·원장 집계. 쓰기는 point-command 만 한다.
 * Primary DB 를 읽기 전용 계정으로 읽는다 (Read Replica 아님 — 사용 직후 잔액이 바로 보여야 한다).
 */
@SpringBootApplication
public class PointQueryApplication {
    public static void main(String[] args) {
        SpringApplication.run(PointQueryApplication.class, args);
    }
}
