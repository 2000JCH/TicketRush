package com.ticketrush.ticketrush.domain.seat.repository;

import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 계정당 이벤트별 동시 진행 예약 1건 제한 키
 * (redis-design.md 8번, key: active_reservation:{eventId}:{accountId}).
 *
 * 값에는 "이번 시도로 무엇을 홀드했는지"를 남겨 해제 시 무엇을 되돌릴지 판단하는 데 쓴다
 * (형식은 SeatService가 정한다). TTL은 홀드 TTL과 동일하게 시작하지만, 4번의 `hold` 키와
 * 마찬가지로 실제 만료 처리는 `HoldScheduleRepository`(4-1번)가 담당하고 이 TTL은 보조
 * 안전장치일 뿐이다.
 */
@Repository
@RequiredArgsConstructor
public class ActiveReservationRepository {

    private static final String KEY_PREFIX = "active_reservation:";

    private final StringRedisTemplate redisTemplate;

    /** @return 선점 성공 여부. 이미 진행 중인 시도가 있으면 false. */
    public boolean tryStart(Long eventId, Long accountId, String value, Duration ttl) {
        Boolean result = redisTemplate.opsForValue().setIfAbsent(key(eventId, accountId), value, ttl);
        return Boolean.TRUE.equals(result);
    }

    public Optional<String> find(Long eventId, Long accountId) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(key(eventId, accountId)));
    }

    /**
     * 이미 선점된 키의 TTL만 다시 세팅한다(결제 요청 시 결제 처리 타임아웃으로 재설정, redis-design.md
     * 8번). `tryStart`와 달리 SETNX가 아니라 그냥 SET이다 — 이미 소유자가 정해진 키를 그 소유자가
     * 갱신하는 것이므로 조건부 쓰기가 필요 없다.
     */
    public void refresh(Long eventId, Long accountId, String value, Duration ttl) {
        redisTemplate.opsForValue().set(key(eventId, accountId), value, ttl);
    }

    public void end(Long eventId, Long accountId) {
        redisTemplate.delete(key(eventId, accountId));
    }

    private String key(Long eventId, Long accountId) {
        return KEY_PREFIX + eventId + ":" + accountId;
    }
}
