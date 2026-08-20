package com.ticketrush.ticketrush.domain.seat.repository;

import com.ticketrush.ticketrush.domain.seat.entity.SeatState;
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
    private static final String SEAT_FIELD_PREFIX = "seat:";
    private static final String STANDING_FIELD_PREFIX = "standing:";
    private static final String HELD_VALUE = "HELD";

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

    /**
     * AVAILABLE(필드 없음) → HELD 원자 전이(decisions.md 1번). HSETNX는 필드가 없을 때만 값을 쓰는
     * 단일 명령이라 Redis 싱글 스레드 특성상 그 자체로 원자적이다 — 별도 Lua 스크립트 없이도
     * "이미 HELD면 실패" 조건을 보장할 수 있어(구현 단계에서 단순화) Lua는 쓰지 않는다.
     *
     * @return 홀드 성공 여부. 이미 HELD면 false.
     */
    public boolean holdSeat(Long eventId, Long seatId) {
        Boolean result = redisTemplate.opsForHash().putIfAbsent(key(eventId), seatField(seatId), HELD_VALUE);
        return Boolean.TRUE.equals(result);
    }

    /** "필드 없음 = AVAILABLE" 규약이므로 필드를 지우면 AVAILABLE로 돌아간다. */
    public void releaseSeat(Long eventId, Long seatId) {
        redisTemplate.opsForHash().delete(key(eventId), seatField(seatId));
    }

    /**
     * 스탠딩 잔여 수량 차감. HINCRBY 자체가 원자적이라 별도 락이 필요 없다(decisions.md 1번).
     * 차감 결과가 음수면(동시에 여러 요청이 마지막 남은 수량을 다투는 경우) 즉시 롤백하고 매진으로 처리한다
     * (redis-design.md 3번).
     *
     * @return 홀드 성공 여부. 매진이면 false.
     */
    public boolean holdStanding(Long eventId, Long sectionId, int quantity) {
        Long remaining = redisTemplate.opsForHash()
                .increment(key(eventId), standingField(sectionId), -quantity);
        if (remaining < 0) {
            redisTemplate.opsForHash().increment(key(eventId), standingField(sectionId), quantity);
            return false;
        }
        return true;
    }

    public void releaseStanding(Long eventId, Long sectionId, int quantity) {
        redisTemplate.opsForHash().increment(key(eventId), standingField(sectionId), quantity);
    }

    /** 좌석 여러 개의 현재 상태를 한 번에 조회(HMGET). 값이 없으면 AVAILABLE로 간주한다. */
    public Map<Long, SeatState> findSeatStatuses(Long eventId, List<Long> seatIds) {
        if (seatIds.isEmpty()) {
            return Map.of();
        }
        List<Object> fields = seatIds.stream().map(seatId -> (Object) seatField(seatId)).toList();
        List<Object> values = redisTemplate.opsForHash().multiGet(key(eventId), fields);

        Map<Long, SeatState> statuses = new LinkedHashMap<>();
        for (int i = 0; i < seatIds.size(); i++) {
            statuses.put(seatIds.get(i), values.get(i) == null ? SeatState.AVAILABLE : SeatState.HELD);
        }
        return statuses;
    }

    private String seatField(Long seatId) {
        return SEAT_FIELD_PREFIX + seatId;
    }

    private String standingField(Long sectionId) {
        return STANDING_FIELD_PREFIX + sectionId;
    }

    private String key(Long eventId) {
        return KEY_PREFIX + eventId;
    }
}
