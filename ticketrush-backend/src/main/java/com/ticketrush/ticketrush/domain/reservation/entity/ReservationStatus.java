package com.ticketrush.ticketrush.domain.reservation.entity;

/**
 * 예약/결제 상태 (db-schema.md 5번, decisions.md 5번 Saga).
 * SEAT_HELD는 이 ENUM에 없다 — 결제 요청 전 홀드 단계는 DB 행 자체가 없고 Redis에만 존재한다.
 */
public enum ReservationStatus {
    PAYMENT_REQUESTED,
    PAYMENT_CONFIRMED,
    PAYMENT_FAILED,
    SEAT_RELEASED
}
