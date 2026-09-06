package com.ticketrush.ticketrush.domain.reservation.dto;

import com.ticketrush.ticketrush.domain.reservation.entity.Reservation;

/**
 * 결제 요청(POST /reservations) 응답. {@code amount}/{@code orderName}은 프론트가 곧바로
 * PortOne SDK의 requestPayment(totalAmount/orderName)로 넘길 수 있도록 함께 내려준다 —
 * 금액을 프론트에서 다시 계산하면 서버 기준값과 어긋날 수 있어 서버가 확정한 값을 그대로 준다.
 */
public record ReservationResponse(
        Long reservationId, String status, String pgPaymentId, int amount, String orderName) {

    public static ReservationResponse of(Reservation reservation) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getStatus().name(),
                reservation.getPgPaymentId(),
                reservation.getAmount(),
                reservation.getEvent().getName());
    }
}
