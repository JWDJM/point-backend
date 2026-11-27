package com.pointplatform.common.security;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.converter.RsaKeyConverters;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;

/**
 * JWT 자체 검증 (user 쪽이 RS256 으로 서명, hybrid-msa.md §4) — 요청마다 발급 서비스를 호출하지 않는다.
 * <ul>
 *   <li>운영: 공개키 {@code JWT_PUBLIC_KEY} (X.509 PEM). 발급 서비스와 같은 키 쌍</li>
 *   <li>local: {@code JWT_JWK_SET_URI} → 발급 서비스 /api/auth/jwks.json (임시 키, 받아온 키는 캐시)</li>
 * </ul>
 * 발급자(iss)도 확인한다 — 다른 발급자가 만든 토큰은 서명이 맞아도 거부.
 */
public final class JwtVerification {

    private JwtVerification() {}

    public static JwtDecoder decoder(String publicKeyPem, String jwkSetUri, String issuer) {
        NimbusJwtDecoder decoder;
        if (publicKeyPem != null && !publicKeyPem.isBlank()) {
            RSAPublicKey key = RsaKeyConverters.x509().convert(
                    new ByteArrayInputStream(publicKeyPem.getBytes(StandardCharsets.UTF_8)));
            decoder = NimbusJwtDecoder.withPublicKey(key).signatureAlgorithm(SignatureAlgorithm.RS256).build();
        } else if (jwkSetUri != null && !jwkSetUri.isBlank()) {
            decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).jwsAlgorithm(SignatureAlgorithm.RS256).build();
        } else {
            throw new IllegalStateException("JWT 검증 키가 없습니다: app.auth.public-key (JWT_PUBLIC_KEY) 또는 app.auth.jwk-set-uri (JWT_JWK_SET_URI)");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("app.auth.issuer (JWT_ISSUER) 가 필요합니다");
        }
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }
}
