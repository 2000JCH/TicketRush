package com.ticketrush.ticketrush.domain.queue.repository;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 입장 토큰 (redis-design.md 2번, key: entry_token:{eventId}:{accountId}).
 *
 * 값은 무작위 UUID다. 검증은 "클라이언트가 X-Entry-Token으로 보낸 값 == Redis에 저장된 값"으로
 * 이뤄지고 토큰 자체에 담을 정보가 없으므로, JWT처럼 서명·클레임을 넣을 필요가 없다.
 *
 * TTL이 좌석 홀드 TTL과 같은 것은 의도된 것이다(decisions.md 4번) — 다만 카운트 시작점이 달라서
 * (토큰은 대기열 통과 시점, 홀드는 좌석 선택 시점) 좌석을 고르는 데 시간을 쓰면 토큰이 먼저 만료될 수 있다.
 * 그래서 2주차에 홀드가 성공하면 토큰 TTL을 홀드 만료 시각에 맞춰 갱신한다.
 */
@Repository
public class EntryTokenRepository {

    private static final String KEY_PREFIX = "entry_token:";

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    public EntryTokenRepository(
            StringRedisTemplate redisTemplate,
            @Value("${seat.hold-ttl-millis}") long holdTtlMillis) {
        this.redisTemplate = redisTemplate;
        this.ttl = Duration.ofMillis(holdTtlMillis);
    }

    public String issue(Long eventId, Long accountId) {
        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(key(eventId, accountId), token, ttl);
        return token;
    }

    public Optional<String> find(Long eventId, Long accountId) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(key(eventId, accountId)));
    }

    private String key(Long eventId, Long accountId) {
        return KEY_PREFIX + eventId + ":" + accountId;
    }
}
