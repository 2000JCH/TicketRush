package com.ticketrush.ticketrush.domain.reservation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 결제 요청 body(api-design.md 5번). 지정석은 seatIds, 스탠딩은 quantity를 보낸다 —
 * 좌석 홀드 요청과 동일한 관례. idempotencyKey는 클라이언트가 매 요청마다 새로 생성해 보낸다.
 */
public record PaymentRequest(

        @NotNull(message = "eventId는 필수입니다.")
        Long eventId,

        @NotNull(message = "sectionId는 필수입니다.")
        Long sectionId,

        List<Long> seatIds,

        Integer quantity,

        @NotBlank(message = "idempotencyKey는 필수입니다.")
        String idempotencyKey
) {
}
