package com.ticketrush.ticketrush.domain.seat.lock;

import com.ticketrush.ticketrush.domain.event.repository.SeatRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * DB 비관적 락(`SELECT ... FOR UPDATE`) 기반 그룹 홀드 락. `seat` 테이블 행을 좌석 상태의 원천
 * (Redis)과는 무관하게 순수한 뮤텍스로만 쓴다 — 같은 좌석 쌍을 노리는 다른 그룹 홀드 요청을
 * 이 트랜잭션이 끝날 때까지 대기시키는 용도다.
 *
 * REQUIRES_NEW로 별도 트랜잭션을 새로 열어, 락을 쥐고 있는 구간을 action 실행 동안으로만
 * 좁힌다 — 호출부(SeatService.hold, readOnly 트랜잭션)에 그대로 편승하면 락이 필요 이상으로
 * 오래(스케줄 등록 등 이후 처리까지) 유지되어 Redisson 구현과 락 보유 시간이 달라져 벤치마크
 * 비교 조건이 어긋난다.
 */
@Component
@ConditionalOnProperty(name = "group-hold.lock-strategy", havingValue = "db")
@RequiredArgsConstructor
public class DbPessimisticLockGroupHoldLockStrategy implements GroupHoldLockStrategy {

    private final SeatRepository seatRepository;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void withLock(Long eventId, List<Long> seatIds, Runnable action) {
        List<Long> sorted = seatIds.stream().sorted().toList();
        seatRepository.findAllByIdInForUpdate(sorted);
        action.run();
    }
}
