package com.ticketrush.ticketrush.domain.seat.scheduler;

import com.ticketrush.ticketrush.domain.seat.service.SeatService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 홀드 만료를 주기적으로 처리한다(redis-design.md 4-1번). 대기열 입장 토큰 발급
 * (`EntryTokenScheduler`)과 같은 폴링 패턴이다 — Redis Keyspace Notification의 pub/sub 유실
 * 위험을 피하기 위해 구현 단계에서 이 방식으로 설계했다(사용자 확인 완료).
 */
@Component
@RequiredArgsConstructor
public class HoldExpiryScheduler {

    private final SeatService seatService;

    @Scheduled(fixedDelayString = "${seat.hold-expiry-check-interval-millis}")
    public void releaseExpiredHolds() {
        seatService.releaseExpiredHolds();
    }
}
