package com.ticketrush.ticketrush.domain.reservation.entity;

import com.ticketrush.ticketrush.domain.account.entity.Account;
import com.ticketrush.ticketrush.domain.event.entity.Event;
import com.ticketrush.ticketrush.domain.event.entity.Section;
import com.ticketrush.ticketrush.global.entity.BaseTimeEntity;
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
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 예약/결제 부모 테이블 (db-schema.md 5번). 상태 전이는 decisions.md 5번의 Saga를 그대로 따른다 —
 * `PAYMENT_REQUESTED → PAYMENT_CONFIRMED`(정상) / `→ PAYMENT_FAILED → SEAT_RELEASED`(보상).
 *
 * 2주차 "Saga 상태머신" 단계에서는 실제 PG(포트원) 호출 없이 상태 전이 로직만 구현한다(사용자
 * 확인 완료) — `pg_payment_id` 채우기, 실제 웹훅 서명 검증, Kafka exactly-once 발행은 3주차
 * "결제 연동"에서 이어서 구현한다.
 */
@Getter
@Entity
@Table(name = "reservation")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "section_id", nullable = false)
    private Section section;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private int amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 64)
    private String idempotencyKey;

    @Column(name = "pg_payment_id", length = 64)
    private String pgPaymentId;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    private Reservation(
            Account account, Event event, Section section, int quantity, int amount, String idempotencyKey) {
        this.account = account;
        this.event = event;
        this.section = section;
        this.quantity = quantity;
        this.amount = amount;
        this.status = ReservationStatus.PAYMENT_REQUESTED;
        this.idempotencyKey = idempotencyKey;
        this.requestedAt = LocalDateTime.now();
    }

    public static Reservation request(
            Account account, Event event, Section section, int quantity, int amount, String idempotencyKey) {
        return new Reservation(account, event, section, quantity, amount, idempotencyKey);
    }

    public void confirm() {
        this.status = ReservationStatus.PAYMENT_CONFIRMED;
        this.confirmedAt = LocalDateTime.now();
    }

    public void fail() {
        this.status = ReservationStatus.PAYMENT_FAILED;
    }

    /** Saga 보상 완료 — 좌석/스탠딩 반납까지 끝났다는 뜻(decisions.md 5번). */
    public void release() {
        this.status = ReservationStatus.SEAT_RELEASED;
    }

    public boolean isRequested() {
        return status == ReservationStatus.PAYMENT_REQUESTED;
    }
}
