package com.ticketrush.ticketrush.domain.event.entity;

import com.ticketrush.ticketrush.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 개별 좌석 (db-schema.md 4번). SEATED 구역에만 존재한다.
 * 판매 상태(AVAILABLE/HELD)는 여기 두지 않는다 — 상태의 원천은 Redis이고(decisions.md 1번),
 * 이 테이블은 "이 좌석이 존재한다"는 정적 사실만 담는다.
 *
 * 실제 행 삽입은 이 엔티티가 아니라 SeatBulkInsertRepository(JdbcTemplate)가 한다 —
 * 구역 하나가 수천 좌석이 될 수 있어 JPA로는 INSERT가 좌석 수만큼 낱개로 나가기 때문이다.
 */
@Getter
@Entity
@Table(
        name = "seat",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_seat", columnNames = {"section_id", "row_no", "seat_no"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Seat extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "section_id", nullable = false)
    private Section section;

    @Column(name = "row_no", nullable = false)
    private int rowNo;

    @Column(name = "seat_no", nullable = false)
    private int seatNo;
}
