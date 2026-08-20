package com.ticketrush.ticketrush.domain.seat.dto;

import java.time.LocalDateTime;

public record SeatHoldResponse(String status, LocalDateTime holdExpiresAt) {

    public static SeatHoldResponse held(LocalDateTime holdExpiresAt) {
        return new SeatHoldResponse("SEAT_HELD", holdExpiresAt);
    }
}
