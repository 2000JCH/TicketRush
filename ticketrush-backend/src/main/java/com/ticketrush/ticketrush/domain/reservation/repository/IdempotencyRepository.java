package com.ticketrush.ticketrush.domain.reservation.repository;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 결제 요청 멱등성 (redis-design.md 5번, key: idempotency:{idempotencyKey}).
 * 결제 요청 API가 PG를 호출하기 직전 SETNX로 선점하고, 실패하면 중복 요청으로 거절한다.
 * Redis가 죽어 이 키가 유실되는 경우의 2차 방어선은 `reservation.idempotency_key` DB UNIQUE 제약.
 */
@Repository
@RequiredArgsConstructor
public class IdempotencyRepository {

    private static final String KEY_PREFIX = "idempotency:";

    private final StringRedisTemplate redisTemplate;

    /** @return 선점 성공 여부. 이미 같은 키로 처리 중이면 false. */
    public boolean tryClaim(String idempotencyKey, Duration ttl) {
        Boolean result = redisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + idempotencyKey, "1", ttl);
        return Boolean.TRUE.equals(result);
    }
}
