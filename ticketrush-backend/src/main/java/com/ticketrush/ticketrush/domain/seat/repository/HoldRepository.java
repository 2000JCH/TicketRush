package com.ticketrush.ticketrush.domain.seat.repository;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 홀드 키 (redis-design.md 4번, key: hold:{eventId}:{seatId} / hold:{eventId}:{accountId}:{sectionId}).
 *
 * 실제 만료 처리(좌석 상태 롤백)는 이 키의 TTL 만료가 아니라 `HoldScheduleRepository`(4-1번)의
 * 스케줄 기반으로 이뤄진다 — 이 키의 TTL은 스케줄러가 멈추는 극단적 상황에서도 메모리가 무한히
 * 쌓이지 않게 하는 보조 안전장치일 뿐이다(구현 단계에서 Keyspace Notification 대신 이 방식으로
 * 재설계, 사용자 확인 완료).
 */
@Repository
@RequiredArgsConstructor
public class HoldRepository {

    private static final String KEY_PREFIX = "hold:";

    private final StringRedisTemplate redisTemplate;

    public void holdSeat(Long eventId, Long seatId, Long accountId, Duration ttl) {
        redisTemplate.opsForValue().set(seatKey(eventId, seatId), accountId.toString(), ttl);
    }

    public void releaseSeat(Long eventId, Long seatId) {
        redisTemplate.delete(seatKey(eventId, seatId));
    }

    public void holdStanding(Long eventId, Long accountId, Long sectionId, int quantity, Duration ttl) {
        redisTemplate.opsForValue()
                .set(standingKey(eventId, accountId, sectionId), String.valueOf(quantity), ttl);
    }

    public void releaseStanding(Long eventId, Long accountId, Long sectionId) {
        redisTemplate.delete(standingKey(eventId, accountId, sectionId));
    }

    private String seatKey(Long eventId, Long seatId) {
        return KEY_PREFIX + eventId + ":" + seatId;
    }

    private String standingKey(Long eventId, Long accountId, Long sectionId) {
        return KEY_PREFIX + eventId + ":" + accountId + ":" + sectionId;
    }
}
