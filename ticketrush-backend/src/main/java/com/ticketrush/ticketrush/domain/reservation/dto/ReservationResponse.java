package com.ticketrush.ticketrush.domain.reservation.dto;

import com.ticketrush.ticketrush.domain.reservation.entity.Reservation;

public record ReservationResponse(Long reservationId, String status, String pgPaymentId) {

    public static ReservationResponse of(Reservation reservation) {
        return new ReservationResponse(
                reservation.getId(), reservation.getStatus().name(), reservation.getPgPaymentId());
    }
}
