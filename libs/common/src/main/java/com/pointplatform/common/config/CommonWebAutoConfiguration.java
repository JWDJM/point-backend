package com.pointplatform.common.config;

import com.pointplatform.common.error.GlobalExceptionHandler;
import com.pointplatform.common.security.LoginUserArgumentResolver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Import;

/**
 * libs/common 을 의존하는 서블릿 웹 서비스에 공통 응답·에러 처리와 {@code @LoginUser} 주입을 등록한다.
 * 서비스의 컴포넌트 스캔 범위(com.pointplatform.&lt;서비스&gt;) 밖에 있으므로 자동 설정으로 등록한다.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Import({GlobalExceptionHandler.class, LoginUserArgumentResolver.class})
public class CommonWebAutoConfiguration {
}
