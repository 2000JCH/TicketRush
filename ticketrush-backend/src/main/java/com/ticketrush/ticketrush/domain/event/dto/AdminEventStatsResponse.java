package com.ticketrush.ticketrush.domain.event.dto;

import java.time.LocalDateTime;

/**
 * 관리자 콘서트 현황 한 줄. `sold`/`remaining`은 확정 매수 기준(취소분 제외)이고,
 * `remaining`은 음수가 되지 않도록 0에서 클램프한다(스탠딩 초과판매 여지 등 대비).
 */
public record AdminEventStatsResponse(
        Long eventId,
        String eventName,
        LocalDateTime openAt,
        long capacity,
        long sold,
        long remaining,
        long confirmedReservations,
        long confirmedAmount) {
}
