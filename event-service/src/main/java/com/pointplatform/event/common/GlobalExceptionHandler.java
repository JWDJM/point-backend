package com.pointplatform.event.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 잘못된 입력을 Spring 기본 에러(500/400) 대신 ApiResponse 포맷으로 응답한다 (monolith GlobalExceptionHandler 와 동일 코드) */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String INVALID_MESSAGE = "요청 값이 올바르지 않습니다.";

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiResponse<Void>> unauthorized(AuthenticationException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.of("UNAUTHORIZED", null, "로그인이 필요합니다."));
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class,
            MissingRequestHeaderException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiResponse<Void>> invalidRequest(Exception e) {
        return ResponseEntity.badRequest().body(ApiResponse.of("INVALID_REQUEST", null, INVALID_MESSAGE));
    }
}
