package com.ticketrush.ticketrush.domain.reservation.dto;

import com.ticketrush.ticketrush.domain.reservation.entity.Reservation;
import com.ticketrush.ticketrush.domain.reservation.entity.ReservationSeat;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * GET /reservations/me(목록), GET /reservations/{id}(상세) 공용 응답. api-design.md 5번의 상세
 * 조회 예시(reservationId/status/amount/confirmedAt)에 eventId/eventName/quantity/requestedAt과
 * 좌석 목록(지정석)을 더했다 — "어느 콘서트의 몇 매짜리 예약이고 몇 번 자리인지"를 내 예약 화면에서
 * 보여주기 위해 구현 단계에서 확정. 스탠딩 예약은 seats가 빈 배열이고 quantity로만 표시한다.
 */
public record ReservationDetailResponse(
        Long reservationId,
        Long eventId,
        String eventName,
        String status,
        int quantity,
        int amount,
        List<SeatInfo> seats,
        LocalDateTime requestedAt,
        LocalDateTime confirmedAt) {

    /** 지정석 한 자리. sectionName + "행-번" 조합으로 화면에 표시한다. */
    public record SeatInfo(String sectionName, int rowNo, int seatNo) {
    }

    public static ReservationDetailResponse of(Reservation reservation, List<ReservationSeat> reservationSeats) {
        List<SeatInfo> seats = reservationSeats.stream()
                .map(rs -> new SeatInfo(
                        rs.getSeat().getSection().getName(),
                        rs.getSeat().getRowNo(),
                        rs.getSeat().getSeatNo()))
                .sorted(Comparator.comparingInt(SeatInfo::rowNo).thenComparingInt(SeatInfo::seatNo))
                .toList();
        return new ReservationDetailResponse(
                reservation.getId(),
                reservation.getEvent().getId(),
                reservation.getEvent().getName(),
                reservation.getStatus().name(),
                reservation.getQuantity(),
                reservation.getAmount(),
                seats,
                reservation.getRequestedAt(),
                reservation.getConfirmedAt());
    }
}
