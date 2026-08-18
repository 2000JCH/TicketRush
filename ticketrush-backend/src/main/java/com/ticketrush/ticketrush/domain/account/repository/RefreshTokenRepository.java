package com.ticketrush.ticketrush.domain.account.repository;

import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Refresh Token 저장소 (redis-design.md 9번, key: refresh_token:{accountId}).
 * 키가 accountId 단위라 새 로그인이 기존 값을 덮어쓴다 — 계정당 1개만 유지되므로
 * 다중 기기 동시 로그인은 지원하지 않는다(decisions.md 3번, 사용자 확인 완료).
 */
@Repository
public class RefreshTokenRepository {

    private static final String KEY_PREFIX = "refresh_token:";

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    public RefreshTokenRepository(
            StringRedisTemplate redisTemplate,
            @Value("${jwt.refresh-token-expiration}") long refreshTokenExpirationMillis) {
        this.redisTemplate = redisTemplate;
        this.ttl = Duration.ofMillis(refreshTokenExpirationMillis);
    }

    public void save(Long accountId, String refreshToken) {
        redisTemplate.opsForValue().set(key(accountId), refreshToken, ttl);
    }

    public Optional<String> findByAccountId(Long accountId) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(key(accountId)));
    }

    /** 로그아웃 시 호출. 이 키가 사라지면 해당 Refresh Token은 즉시 무효가 된다. */
    public void deleteByAccountId(Long accountId) {
        redisTemplate.delete(key(accountId));
    }

    private String key(Long accountId) {
        return KEY_PREFIX + accountId;
    }
}
