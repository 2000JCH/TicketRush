package com.ticketrush.ticketrush.domain.event.dto;

import com.ticketrush.ticketrush.domain.event.entity.Event;
import java.time.LocalDateTime;

/**
 * 이벤트 목록용. 구역/좌석은 담지 않는다 — 목록 화면에 필요 없고,
 * 이벤트마다 구역을 조회하면 목록 한 번에 쿼리가 이벤트 수만큼 늘어난다.
 */
public record EventSummaryResponse(Long id, String name, LocalDateTime openAt) {

    public static EventSummaryResponse from(Event event) {
        return new EventSummaryResponse(event.getId(), event.getName(), event.getOpenAt());
    }
}
