package com.ticketrush.ticketrush.domain.reservation.dto;

import com.ticketrush.ticketrush.domain.reservation.entity.Reservation;
import java.time.LocalDateTime;

/**
 * GET /reservations/me(목록), GET /reservations/{id}(상세) 공용 응답. api-design.md 5번의 상세
 * 조회 예시(reservationId/status/amount/confirmedAt)에 eventId/quantity/requestedAt을 더했다 —
 * 목록 조회에서 "어느 이벤트의 몇 매짜리 예약인지"가 필요해 구현 단계에서 확정.
 */
public record ReservationDetailResponse(
        Long reservationId,
        Long eventId,
        String status,
        int quantity,
        int amount,
        LocalDateTime requestedAt,
        LocalDateTime confirmedAt) {

    public static ReservationDetailResponse of(Reservation reservation) {
        return new ReservationDetailResponse(
                reservation.getId(),
                reservation.getEvent().getId(),
                reservation.getStatus().name(),
                reservation.getQuantity(),
                reservation.getAmount(),
                reservation.getRequestedAt(),
                reservation.getConfirmedAt());
    }
}
