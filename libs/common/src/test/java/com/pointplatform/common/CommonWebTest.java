package com.pointplatform.common;

import com.pointplatform.common.error.ApiResponse;
import com.pointplatform.common.error.BusinessException;
import com.pointplatform.common.error.CommonErrorCode;
import com.pointplatform.common.error.ErrorCode;
import com.pointplatform.common.error.GlobalExceptionHandler;
import com.pointplatform.common.security.LoginUser;
import com.pointplatform.common.security.LoginUserArgumentResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** libs/common: 응답 형식 {code, data, message} 와 @LoginUser 주입이 서비스와 무관하게 동작하는지 */
class CommonWebTest {

    /** 서비스가 자기 도메인 코드를 정의하는 방식 (예: PointErrorCode) */
    enum SampleDomainCode implements ErrorCode {
        SAMPLE_CONFLICT;
        @Override public HttpStatus status() { return HttpStatus.CONFLICT; }
        @Override public String message() { return "샘플 충돌"; }
    }

    @RestController
    static class SampleController {
        @GetMapping("/me")
        ApiResponse<Long> me(@LoginUser long userId) { return ApiResponse.ok(userId); }

        @GetMapping("/conflict")
        ApiResponse<Void> conflict() { throw new BusinessException(SampleDomainCode.SAMPLE_CONFLICT); }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SampleController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new LoginUserArgumentResolver())
            .build();

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void 에러_응답의_code_는_enum_이름() {
        ApiResponse<Void> body = ApiResponse.error(CommonErrorCode.UNAUTHORIZED);
        assertThat(body.code()).isEqualTo("UNAUTHORIZED");
        assertThat(body.message()).isEqualTo("로그인이 필요합니다.");
        assertThat(body.data()).isNull();
    }

    @Test
    void 도메인_코드도_같은_형식과_상태로_응답() throws Exception {
        mvc.perform(get("/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SAMPLE_CONFLICT"))
                .andExpect(jsonPath("$.message").value("샘플 충돌"));
    }

    @Test
    void JWT_sub_를_사용자_id_로_주입() throws Exception {
        authenticate("42");
        mvc.perform(get("/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data").value(42));
    }

    @Test
    void sub_가_숫자가_아니면_401() throws Exception {
        authenticate("not-a-number");
        mvc.perform(get("/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 인증이_없으면_401() throws Exception {
        mvc.perform(get("/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private static void authenticate(String sub) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(sub).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
