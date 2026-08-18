package com.ticketrush.ticketrush.domain.event.dto;

import com.ticketrush.ticketrush.domain.event.entity.Event;
import com.ticketrush.ticketrush.domain.event.entity.Section;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 이벤트 상세 (구역 목록 포함, api-design.md 2번). */
public record EventResponse(
        Long id,
        String name,
        LocalDateTime openAt,
        List<SectionResponse> sections
) {

    /** @param standingRemaining sectionId → 잔여 수량 (STANDING 구역만, Redis에서 읽은 값) */
    public static EventResponse of(Event event, List<Section> sections,
                                   Map<Long, Integer> standingRemaining) {
        List<SectionResponse> sectionResponses = sections.stream()
                .map(section -> SectionResponse.of(section, standingRemaining.get(section.getId())))
                .toList();
        return new EventResponse(event.getId(), event.getName(), event.getOpenAt(), sectionResponses);
    }
}
