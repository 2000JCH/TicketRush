package com.ticketrush.ticketrush.domain.seat.dto;

import com.ticketrush.ticketrush.domain.seat.entity.SeatState;

public record SeatStatusResponse(Long seatId, int rowNo, int seatNo, SeatState status) {
}
