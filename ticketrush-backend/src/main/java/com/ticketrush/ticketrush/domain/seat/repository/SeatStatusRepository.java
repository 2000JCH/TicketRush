package com.ticketrush.ticketrush.domain.seat.repository;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 좌석 상태 + 스탠딩 잔여 수량 통합 Hash (redis-design.md 3번, key: seat_status:{eventId}).
 *
 * 지정석은 등록 시 필드를 만들지 않는다 — "필드 없음 = AVAILABLE"이 규약이라 좌석 수만큼
 * 필드를 미리 채우면 메모리만 낭비된다. 스탠딩만 잔여 수량을 명시적으로 세팅한다.
 */
@Repository
@RequiredArgsConstructor
public class SeatStatusRepository {

    private static final String KEY_PREFIX = "seat_status:";
    private static final String STANDING_FIELD_PREFIX = "standing:";

    /**
     * 초기화 완료 표시 필드.
     * 지정석만 있는 이벤트는 세팅할 필드가 하나도 없는데, Redis는 필드가 없는 Hash를 보관하지 않아
     * 키 자체가 생기지 않는다. 그러면 "키 없음 = 데이터 유실"로 보는 판정(decisions.md 1번)에
     * 정상 이벤트가 걸려버리므로, 초기화되었다는 사실 자체를 남기는 필드를 항상 하나 넣는다.
     */
    private static final String INITIALIZED_FIELD = "meta:initialized";

    private final StringRedisTemplate redisTemplate;

    /** @param standingQuantities sectionId → 총 수량 (STANDING 구역만) */
    public void initialize(Long eventId, Map<Long, Integer> standingQuantities) {
        Map<String, String> fields = new HashMap<>();
        fields.put(INITIALIZED_FIELD, "1");
        standingQuantities.forEach(
                (sectionId, quantity) ->
                        fields.put(STANDING_FIELD_PREFIX + sectionId, String.valueOf(quantity)));
        redisTemplate.opsForHash().putAll(key(eventId), fields);
    }

    public void delete(Long eventId) {
        redisTemplate.delete(key(eventId));
    }

    /**
     * 스탠딩 구역들의 실시간 잔여 수량. 값이 없으면 결과 맵에 담기지 않는다.
     * (Redis 유실 중 조회되는 경우로, 좌석 조회/홀드 API의 rebuild 플래그 처리는 2주차에서 다룬다.)
     */
    public Map<Long, Integer> findStandingRemaining(Long eventId, List<Long> sectionIds) {
        if (sectionIds.isEmpty()) {
            return Map.of();
        }
        List<Object> fields = sectionIds.stream()
                .map(sectionId -> (Object) (STANDING_FIELD_PREFIX + sectionId))
                .toList();
        List<Object> values = redisTemplate.opsForHash().multiGet(key(eventId), fields);

        Map<Long, Integer> remaining = new LinkedHashMap<>();
        for (int i = 0; i < sectionIds.size(); i++) {
            Object value = values.get(i);
            if (value != null) {
                remaining.put(sectionIds.get(i), Integer.valueOf(value.toString()));
            }
        }
        return remaining;
    }

    private String key(Long eventId) {
        return KEY_PREFIX + eventId;
    }
}
