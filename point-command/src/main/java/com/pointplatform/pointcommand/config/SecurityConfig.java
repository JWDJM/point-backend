package com.pointplatform.pointcommand.config;

import com.pointplatform.common.security.JsonAuthenticationEntryPoint;
import com.pointplatform.common.security.JwtVerification;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

/**
 * 인증: {@code Authorization: Bearer <JWT>} 자체 검증 (libs/common JwtVerification).
 * 공개 경로: /api/system/point-command/** (운영 검증, OPS_API_ENABLED 로 제어), actuator.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        JsonAuthenticationEntryPoint unauthorized = new JsonAuthenticationEntryPoint(objectMapper);
        return http
                .csrf(csrf -> csrf.disable())   // 쿠키 인증 없음 (Bearer 전용) → CSRF 대상 아님
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/system/point-command/**", "/actuator/**", "/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()).authenticationEntryPoint(unauthorized))
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthorized))
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${app.auth.public-key:}") String publicKeyPem,
                          @Value("${app.auth.jwk-set-uri:}") String jwkSetUri,
                          @Value("${app.auth.issuer}") String issuer) {
        return JwtVerification.decoder(publicKeyPem, jwkSetUri, issuer);
    }
}
