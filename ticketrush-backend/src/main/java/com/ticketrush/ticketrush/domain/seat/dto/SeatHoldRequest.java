package com.ticketrush.ticketrush.domain.seat.dto;

import java.util.List;

/**
 * 지정석은 seatIds, 스탠딩은 quantity로 요청한다(api-design.md 4번).
 * seatIds는 1~2개까지 허용된다 — 2개는 그룹 홀드(decisions.md 2번)로, 두 좌석 다 잡거나
 * 둘 다 실패하도록 락으로 처리한다.
 */
public record SeatHoldRequest(Long sectionId, List<Long> seatIds, Integer quantity) {
}
