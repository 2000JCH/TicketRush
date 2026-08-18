package com.ticketrush.ticketrush.domain.event.dto;

import com.ticketrush.ticketrush.domain.event.entity.SectionType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * type에 따라 쓰는 필드가 갈린다 (db-schema.md 3번).
 * SEATED → rowCount + seatsPerRow / STANDING → totalQuantity
 * 어느 쪽을 채워야 하는지는 값끼리의 관계라 애노테이션으로 표현하지 않고 EventService가 검증한다.
 */
public record SectionRequest(

        @NotBlank(message = "구역명은 필수입니다.")
        @Size(max = 50, message = "구역명은 50자를 넘을 수 없습니다.")
        String name,

        @NotNull(message = "구역 유형(SEATED/STANDING)은 필수입니다.")
        SectionType type,

        @NotNull(message = "가격은 필수입니다.")
        @Min(value = 0, message = "가격은 0 이상이어야 합니다.")
        Integer price,

        @Min(value = 1, message = "행 수는 1 이상이어야 합니다.")
        Integer rowCount,

        @Min(value = 1, message = "행당 좌석 수는 1 이상이어야 합니다.")
        Integer seatsPerRow,

        @Min(value = 1, message = "총 수량은 1 이상이어야 합니다.")
        Integer totalQuantity
) {
}
