package com.ticketrush.ticketrush.domain.seat.lock;

import com.ticketrush.ticketrush.global.exception.BusinessException;
import com.ticketrush.ticketrush.global.exception.ErrorCode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Redis(Redisson RLock) 기반 그룹 홀드 락. seatId 오름차순으로 순서대로 락을 건다 — 서로 다른
 * 요청이 같은 좌석 쌍을 반대 순서로 잡으려 해도 항상 작은 seatId부터 시도하므로 순환 대기(데드락)가
 * 생기지 않는다.
 */
@Component
@ConditionalOnProperty(name = "group-hold.lock-strategy", havingValue = "redis", matchIfMissing = true)
public class RedissonGroupHoldLockStrategy implements GroupHoldLockStrategy {

    private static final String KEY_PREFIX = "group-hold-lock:";

    private final RedissonClient redissonClient;
    private final long waitMillis;
    private final long leaseMillis;

    public RedissonGroupHoldLockStrategy(
            RedissonClient redissonClient,
            @Value("${group-hold.lock-wait-millis}") long waitMillis,
            @Value("${group-hold.lock-lease-millis}") long leaseMillis) {
        this.redissonClient = redissonClient;
        this.waitMillis = waitMillis;
        this.leaseMillis = leaseMillis;
    }

    @Override
    public void withLock(Long eventId, List<Long> seatIds, Runnable action) {
        List<Long> sorted = seatIds.stream().sorted().toList();
        Deque<RLock> acquired = new ArrayDeque<>();
        try {
            for (Long seatId : sorted) {
                RLock lock = redissonClient.getLock(KEY_PREFIX + eventId + ":" + seatId);
                boolean success;
                try {
                    success = lock.tryLock(waitMillis, leaseMillis, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new BusinessException(ErrorCode.GROUP_HOLD_LOCK_TIMEOUT);
                }
                if (!success) {
                    throw new BusinessException(ErrorCode.GROUP_HOLD_LOCK_TIMEOUT);
                }
                acquired.push(lock);
            }
            action.run();
        } finally {
            while (!acquired.isEmpty()) {
                RLock lock = acquired.pop();
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
    }
}
