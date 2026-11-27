package com.pointplatform.common.error;

import org.springframework.http.HttpStatus;

/** 모든 서비스가 쓰는 공통 에러 코드 */
public enum CommonErrorCode implements ErrorCode {
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "관리자 권한이 필요합니다."),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "서비스 점검 중입니다. 잠시 후 다시 이용해 주세요.");

    private final HttpStatus status;
    private final String message;

    CommonErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override public HttpStatus status() { return status; }
    @Override public String message() { return message; }
}
