package com.ticketrush.ticketrush.domain.seat.dto;

import java.util.List;

/**
 * 지정석은 seatIds, 스탠딩은 quantity로 요청한다(api-design.md 4번).
 * 이번 단계(좌석 상태 모델)는 단일 좌석 홀드만 다루므로 seatIds는 1개까지만 허용된다 —
 * 2개(그룹 홀드)는 분산락 벤치마크(decisions.md 2번) 이후 단계에서 지원한다.
 */
public record SeatHoldRequest(Long sectionId, List<Long> seatIds, Integer quantity) {
}
