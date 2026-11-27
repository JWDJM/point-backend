package com.pointplatform.common;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.pointplatform.common.security.JwtVerification;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 발급 서비스가 서명한 토큰을 공개키로 자체 검증 (iss 확인 포함) */
class JwtVerificationTest {

    private static final String ISSUER = "point-core";
    private static final KeyPair KEYS = rsa();
    private static final KeyPair OTHER_KEYS = rsa();

    private final JwtDecoder decoder = JwtVerification.decoder(publicPem(KEYS), "", ISSUER);

    @Test
    void 같은_키쌍_같은_발급자면_통과() {
        Jwt jwt = decoder.decode(token(KEYS, ISSUER, "42"));
        assertThat(jwt.getSubject()).isEqualTo("42");
    }

    @Test
    void 발급자가_다르면_거부() {
        assertThatThrownBy(() -> decoder.decode(token(KEYS, "someone-else", "42"))).isInstanceOf(JwtException.class);
    }

    @Test
    void 다른_키로_서명하면_거부() {
        assertThatThrownBy(() -> decoder.decode(token(OTHER_KEYS, ISSUER, "42"))).isInstanceOf(JwtException.class);
    }

    @Test
    void 검증_키가_없으면_기동_실패() {
        assertThatThrownBy(() -> JwtVerification.decoder("", "", ISSUER)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtVerification.decoder(publicPem(KEYS), "", "")).isInstanceOf(IllegalStateException.class);
    }

    static String token(KeyPair keys, String issuer, String sub) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), new JWTClaimsSet.Builder()
                    .issuer(issuer).subject(sub).issueTime(new Date())
                    .expirationTime(new Date(System.currentTimeMillis() + 60_000)).build());
            jwt.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String publicPem(KeyPair keys) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keys.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }

    static KeyPair rsa() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
