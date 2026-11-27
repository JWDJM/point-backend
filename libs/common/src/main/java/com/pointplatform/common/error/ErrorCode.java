package com.pointplatform.common.error;

import org.springframework.http.HttpStatus;

/**
 * 에러 코드 계약. 응답의 {@code code} 는 {@link #name()} 이다.
 * 공통 코드는 {@link CommonErrorCode}, 도메인 코드는 각 서비스가 이 인터페이스를 구현한 enum 으로 갖는다
 * (서비스를 나눠도 다른 도메인의 코드를 알 필요가 없게 — point-infra ADR-0024).
 */
public interface ErrorCode {
    String name();

    HttpStatus status();

    String message();
}
