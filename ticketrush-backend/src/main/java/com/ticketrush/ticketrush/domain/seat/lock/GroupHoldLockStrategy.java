package com.ticketrush.ticketrush.domain.seat.lock;

import java.util.List;

/**
 * 그룹 좌석 홀드(2매 동시 선택) 시의 동시성 제어 전략(decisions.md 2번).
 * Redisson RLock과 DB 비관적 락(`SELECT ... FOR UPDATE`) 두 구현을 동일 조건에서 실측 비교해
 * 채택하기로 확정했다 — 지금은 `group-hold.lock-strategy` 프로퍼티로 전환 가능한 상태로만
 * 만들어두고, 실제 비교(Gatling)는 3주차 부하 테스트에서 진행한다.
 */
public interface GroupHoldLockStrategy {

    /**
     * eventId·seatIds에 대한 락을 잡은 채로 action을 실행한다. 락 획득에 실패하면
     * {@code BusinessException(ErrorCode.GROUP_HOLD_LOCK_TIMEOUT)}을 던진다.
     */
    void withLock(Long eventId, List<Long> seatIds, Runnable action);
}
