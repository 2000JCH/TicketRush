package com.ticketrush.ticketrush.domain.seat.entity;

/**
 * 좌석 판매 상태 (redis-design.md 3번). 상태의 원천은 Redis Hash이고, 값이 없으면
 * AVAILABLE로 간주한다("필드 없음 = AVAILABLE" 규약, 메모리 절약).
 */
public enum SeatState {
    AVAILABLE,
    HELD
}
