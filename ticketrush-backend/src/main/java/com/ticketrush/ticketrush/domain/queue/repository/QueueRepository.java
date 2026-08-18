package com.ticketrush.ticketrush.domain.queue.repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 대기열 순번 (redis-design.md 1번, key: queue:{eventId}).
 * Sorted Set의 score를 진입 시각으로 두면 정렬 순서가 곧 "먼저 온 순서"가 된다 — decisions.md 4번의
 * 공정성("먼저 온 사람이 먼저 산다")을 자료구조 자체로 보장하는 부분이다.
 */
@Repository
@RequiredArgsConstructor
public class QueueRepository {

    private static final String KEY_PREFIX = "queue:";

    private final StringRedisTemplate redisTemplate;

    /**
     * 대기열 진입. 이미 대기 중이면 기존 순번을 유지한다(NX) —
     * 폴링 중 실수로 다시 호출했다고 해서 뒤로 밀리면 안 되기 때문.
     *
     * @return 새로 진입했으면 true, 이미 대기 중이었으면 false
     */
    public boolean enter(Long eventId, Long accountId) {
        Boolean added = redisTemplate.opsForZSet()
                .addIfAbsent(key(eventId), member(accountId), System.currentTimeMillis());
        return Boolean.TRUE.equals(added);
    }

    /** 1부터 시작하는 순번. 대기열에 없으면 비어 있다. */
    public Optional<Long> findRank(Long eventId, Long accountId) {
        Long rank = redisTemplate.opsForZSet().rank(key(eventId), member(accountId));
        return Optional.ofNullable(rank).map(value -> value + 1);
    }

    /** 앞에서부터 count명. Scheduler가 입장 토큰을 발급할 대상을 뽑는 데 쓴다. */
    public List<Long> findFront(Long eventId, int count) {
        Set<String> members = redisTemplate.opsForZSet().range(key(eventId), 0, count - 1L);
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        return members.stream().map(Long::valueOf).toList();
    }

    /** 입장 토큰을 받은 사람은 대기열에서 빠진다 — 그래야 뒤쪽 순번이 앞으로 당겨진다. */
    public void remove(Long eventId, List<Long> accountIds) {
        if (accountIds.isEmpty()) {
            return;
        }
        Object[] members = accountIds.stream().map(QueueRepository::member).toArray();
        redisTemplate.opsForZSet().remove(key(eventId), members);
    }

    /** 매진/이벤트 종료 시 일괄 만료 (decisions.md 4번 — 개별 이탈은 추정하지 않는다). */
    public void clear(Long eventId) {
        redisTemplate.delete(key(eventId));
    }

    private String key(Long eventId) {
        return KEY_PREFIX + eventId;
    }

    private static String member(Long accountId) {
        return String.valueOf(accountId);
    }
}
