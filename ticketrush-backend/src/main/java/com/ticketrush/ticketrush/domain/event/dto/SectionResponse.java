package com.ticketrush.ticketrush.domain.event.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ticketrush.ticketrush.domain.event.entity.Section;
import com.ticketrush.ticketrush.domain.event.entity.SectionType;

/**
 * 쓰지 않는 필드는 응답에서 아예 빼기 위해 null을 제외한다
 * (SEATED에는 remainingQuantity가, STANDING에는 rowCount가 의미 없음).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SectionResponse(
        Long id,
        String name,
        SectionType type,
        int price,
        Integer rowCount,
        Integer seatsPerRow,
        Integer totalQuantity,
        Integer remainingQuantity
) {

    /** @param remainingQuantity STANDING 구역의 Redis 실시간 잔여 수량 (없으면 null) */
    public static SectionResponse of(Section section, Integer remainingQuantity) {
        return new SectionResponse(
                section.getId(),
                section.getName(),
                section.getType(),
                section.getPrice(),
                section.getRowCount(),
                section.getSeatsPerRow(),
                section.getTotalQuantity(),
                remainingQuantity);
    }
}
