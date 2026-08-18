package com.ticketrush.ticketrush.domain.event.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 이벤트 등록(POST)과 전체 교체(PUT)가 같은 형식을 쓴다 — 수정은 "다시 등록"과 동일하기 때문(사용자 확인 완료).
 */
public record EventRequest(

        @NotBlank(message = "이벤트명은 필수입니다.")
        @Size(max = 200, message = "이벤트명은 200자를 넘을 수 없습니다.")
        String name,

        @NotNull(message = "오픈 시각은 필수입니다.")
        @Future(message = "오픈 시각은 현재 이후여야 합니다.")
        LocalDateTime openAt,

        @Valid
        @NotEmpty(message = "구역을 최소 1개 이상 등록해야 합니다.")
        List<SectionRequest> sections
) {
}
