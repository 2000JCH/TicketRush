package com.ticketrush.ticketrush.domain.seat.repository;

import com.ticketrush.ticketrush.domain.seat.entity.SeatState;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /**
     * Redis 데이터 유실 복구(rebuild, decisions.md 1번 "Redis 장애 시 홀드 상태 복구 전략")용 키.
     * 마커가 있으면 seat_status 데이터가 안전하다는 뜻이라 rebuild를 건너뛴다.
     *
     * redis-design.md 6번 원안은 이 마커를 전역 키(`system:rebuild_epoch`)로 뒀지만, 여기서는
     * 이벤트별로 나눴다(`system:rebuild_epoch:{eventId}`) — 원안은 "재연결 시 활성 이벤트 전체를
     * rebuild"하는 능동적 트리거를 전제했는데, 실제 구현은 "요청 들어온 이벤트만 그때그때"로
     * 단순화됐다(SeatStatusRebuildService). 전역 마커를 그대로 쓰면 이벤트 A 하나만 rebuild하고도
     * 마커가 세팅돼, 아직 rebuild 안 된 이벤트 B/C가 "안전하다"고 잘못 판정된다.
     */
    private static final String REBUILD_EPOCH_PREFIX = "system:rebuild_epoch:";

    /** 동시에 여러 요청이 같은 이벤트를 동시에 rebuild하지 않도록 막는 짧은 TTL 락(redis-design.md 6번). */
    private static final String REBUILD_LOCK_PREFIX = "rebuild:in_progress:";

    private final StringRedisTemplate redisTemplate;

    /** @param standingQuantities sectionId → 총 수량 (STANDING 구역만) */
    public void initialize(Long eventId, Map<Long, Integer> standingQuantities) {
        Map<String, String> fields = new HashMap<>();
        fields.put(INITIALIZED_FIELD, "1");
        standingQuantities.forEach(
                (sectionId, quantity) ->
                        fields.put(STANDING_FIELD_PREFIX + sectionId, String.valueOf(quantity)));
        redisTemplate.opsForHash().putAll(key(eventId), fields);
        // 방금 막 채운 새 이벤트는 그 자체로 "최신 상태"다 — 마커가 없으면 최초 조회에서
        // 불필요한 rebuild(락 획득 시도 + DB 조회)가 한 번 더 돈다.
        redisTemplate.opsForValue().set(REBUILD_EPOCH_PREFIX + eventId, String.valueOf(System.currentTimeMillis()));
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

    /** 마커가 있으면(=재연결일 뿐 데이터 유실 아님) rebuild를 건너뛰어도 안전하다. */
    public boolean isRebuildMarkerPresent(Long eventId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(REBUILD_EPOCH_PREFIX + eventId));
    }

    /** @return 락 획득 성공 여부. 실패하면 이미 다른 요청이 이 이벤트를 rebuild 중이라는 뜻. */
    public boolean tryAcquireRebuildLock(Long eventId, Duration ttl) {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(REBUILD_LOCK_PREFIX + eventId, "1", ttl);
        return Boolean.TRUE.equals(acquired);
    }

    public void releaseRebuildLock(Long eventId) {
        redisTemplate.delete(REBUILD_LOCK_PREFIX + eventId);
    }

    /**
     * DB 기준으로 다시 계산한 점유 좌석/스탠딩 잔여 수량으로 라이브 Hash를 통째로 교체한다.
     * 원 설계(decisions.md 1번)는 별도 스테이징 키에 채운 뒤 RENAME으로 원자적 스왑하는 방식이었지만,
     * 호출부(SeatStatusRebuildService)가 이미 락으로 "rebuild 중에는 아무도 이 Hash를 읽지 않는다"를
     * 보장하므로 直접 교체해도 동일한 안전성을 더 단순하게 얻는다(2026-09-01, 구현 단계에서 단순화 —
     * HSETNX가 Lua를 대신한 것과 같은 패턴).
     */
    public void applyRebuild(Long eventId, Set<Long> occupiedSeatIds, Map<Long, Integer> standingRemaining) {
        String liveKey = key(eventId);
        redisTemplate.delete(liveKey);

        Map<String, String> fields = new HashMap<>();
        fields.put(INITIALIZED_FIELD, "1");
        occupiedSeatIds.forEach(seatId -> fields.put(seatField(seatId), HELD_VALUE));
        standingRemaining.forEach(
                (sectionId, remaining) -> fields.put(standingField(sectionId), String.valueOf(remaining)));
        redisTemplate.opsForHash().putAll(liveKey, fields);

        redisTemplate.opsForValue().set(REBUILD_EPOCH_PREFIX + eventId, String.valueOf(System.currentTimeMillis()));
    }
}
