package com.ticketrush.ticketrush.domain.reservation.entity;

import com.ticketrush.ticketrush.domain.event.entity.Seat;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 지정석 예약에 포함된 개별 좌석 (db-schema.md 6번). `reservation`(부모) 1건에 좌석이 최대 2개
 * 연결될 수 있다 — 이번 단계는 단일 좌석 홀드만 다루므로(그룹 홀드는 분산락 벤치마크 이후) 실제로는
 * 항상 1개다. 스탠딩 예약은 이 테이블에 행을 만들지 않는다.
 *
 * db-schema.md의 생성 컬럼(`active_seat_id`)·CHECK 제약은 `ddl-auto=update`로 표현할 수 없어
 * (CLAUDE.md에 이미 메모됨), Flyway 도입 없이 애플리케이션 레벨 검증으로 대체한다(사용자 확인
 * 완료) — `ReservationSeatRepository.existsActiveBySeatId`가 "같은 좌석에 진행 중인 예약이
 * 이미 있는지"를 조회해 2차 방어선 역할을 한다. 실제로는 Redis 좌석 홀드(seat 도메인)가 이미
 * 동시성을 막아줘서 이 방어선에 걸릴 일이 정상 흐름에서는 없다.
 */
@Getter
@Entity
@Table(name = "reservation_seat")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReservationSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    private ReservationSeat(Reservation reservation, Seat seat) {
        this.reservation = reservation;
        this.seat = seat;
        this.status = ReservationStatus.PAYMENT_REQUESTED;
    }

    public static ReservationSeat of(Reservation reservation, Seat seat) {
        return new ReservationSeat(reservation, seat);
    }

    public void confirm() {
        this.status = ReservationStatus.PAYMENT_CONFIRMED;
    }

    public void release() {
        this.status = ReservationStatus.SEAT_RELEASED;
    }
}
