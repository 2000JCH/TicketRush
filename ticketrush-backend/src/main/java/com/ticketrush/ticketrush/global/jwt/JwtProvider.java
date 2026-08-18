package com.ticketrush.ticketrush.global.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Access / Refresh Token 발급·검증 (decisions.md 3번).
 * Refresh Token도 JWT로 만드는 이유: /auth/refresh에서 토큰만 보고 accountId를 알아내야
 * Redis의 refresh_token:{accountId}를 조회할 수 있기 때문이다(불투명 랜덤 문자열이면 역인덱스가 추가로 필요).
 */
@Component
public class JwtProvider {

    private static final String ROLE_CLAIM = "role";

    private final SecretKey key;
    private final long accessTokenExpirationMillis;
    private final long refreshTokenExpirationMillis;

    public JwtProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-expiration}") long accessTokenExpirationMillis,
            @Value("${jwt.refresh-token-expiration}") long refreshTokenExpirationMillis) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpirationMillis = accessTokenExpirationMillis;
        this.refreshTokenExpirationMillis = refreshTokenExpirationMillis;
    }

    public String createAccessToken(Long accountId, String role) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(accountId))
                .claim(ROLE_CLAIM, role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + accessTokenExpirationMillis))
                .signWith(key)
                .compact();
    }

    /**
     * role을 넣지 않는다 — 재발급 시 DB에서 계정을 다시 읽어 그때의 role로 Access Token을 만든다.
     * jti(고유 식별자)를 넣는 이유: iat/exp는 초 단위라 같은 초에 발급하면 토큰 문자열이 완전히 같아져
     * "재발급 시 기존 토큰을 무효화한다"는 회전이 실제로는 일어나지 않는다.
     */
    public String createRefreshToken(Long accountId) {
        Date now = new Date();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(accountId))
                .issuedAt(now)
                .expiration(new Date(now.getTime() + refreshTokenExpirationMillis))
                .signWith(key)
                .compact();
    }

    /** 위변조/만료 시 JwtException을 던진다. */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String getRole(Claims claims) {
        return claims.get(ROLE_CLAIM, String.class);
    }
}
