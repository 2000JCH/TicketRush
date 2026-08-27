package com.ticketrush.ticketrush.domain.reservation.entity;

import com.ticketrush.ticketrush.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Outbox 패턴 테이블(decisions.md 6번, db-schema.md 7번). reservation 상태를 바꾸는 트랜잭션과
 * 같은 트랜잭션에서 INSERT되고, Debezium(Outbox Event Router SMT)이 binlog 변경을 감지해
 * {@code aggregateType} 값 기준으로 Kafka 토픽에 발행한다. FK는 두지 않는다(테이블 관계 요약 참고
 * — DB 트랜잭션 원자성만으로 reservation과 연결되며 조회 목적의 FK가 아니기 때문).
 *
 * <p>3주차 범위(사용자 확인 완료): outbox 이벤트는 {@code PAYMENT_FAILED} 전이에서만 기록한다 —
 * 결제 실패 시 좌석 반납({@code releaseAfterFailure})을 Kafka Consumer가 트리거하도록 하기 위함.
 * {@code PAYMENT_CONFIRMED} 이후의 정산/알림은 아직 보류 중인 기능이라(api-design.md 남은 항목)
 * outbox 이벤트를 만들지 않는다 — 그 기능이 생기는 시점에 이 엔티티를 그대로 재사용하면 된다.
 */
@Getter
@Entity
@Table(name = "outbox_events")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent extends BaseTimeEntity {

    public static final String AGGREGATE_TYPE_RESERVATION = "reservation";
    public static final String EVENT_TYPE_PAYMENT_FAILED = "PAYMENT_FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "payload", nullable = false, columnDefinition = "JSON")
    private String payload;

    private OutboxEvent(String aggregateType, Long aggregateId, String eventType, String payload) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
    }

    public static OutboxEvent paymentFailed(Long reservationId, String payload) {
        return new OutboxEvent(AGGREGATE_TYPE_RESERVATION, reservationId, EVENT_TYPE_PAYMENT_FAILED, payload);
    }
}
