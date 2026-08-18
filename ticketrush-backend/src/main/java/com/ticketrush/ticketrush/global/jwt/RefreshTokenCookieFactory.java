package com.ticketrush.ticketrush.global.jwt;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Refresh Token을 담을 httpOnly Cookie를 만든다 (decisions.md 3번).
 * JS가 값을 읽지 못하게 해 XSS로 탈취될 위험을 줄이는 것이 목적이므로 httpOnly는 항상 켠다.
 */
@Component
public class RefreshTokenCookieFactory {

    public static final String COOKIE_NAME = "refreshToken";

    /** Refresh Token은 인증 API에서만 필요하므로 다른 경로 요청에는 아예 실려가지 않게 한다. */
    private static final String COOKIE_PATH = "/api/v1/auth";

    private final long refreshTokenExpirationMillis;
    private final boolean secure;

    public RefreshTokenCookieFactory(
            @Value("${jwt.refresh-token-expiration}") long refreshTokenExpirationMillis,
            @Value("${auth.refresh-cookie.secure}") boolean secure) {
        this.refreshTokenExpirationMillis = refreshTokenExpirationMillis;
        this.secure = secure;
    }

    public ResponseCookie create(String refreshToken) {
        return build(refreshToken, Duration.ofMillis(refreshTokenExpirationMillis));
    }

    /** 로그아웃 시 브라우저에 남은 쿠키도 즉시 지운다(서버 쪽 Redis 삭제와 별개). */
    public ResponseCookie expire() {
        return build("", Duration.ZERO);
    }

    private ResponseCookie build(String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(secure)
                .path(COOKIE_PATH)
                // 프론트엔드가 다른 오리진에서 붙게 되면 SameSite=None + Secure로 바꿔야 한다(프론트 스택 미정).
                .sameSite("Lax")
                .maxAge(maxAge)
                .build();
    }
}
