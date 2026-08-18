package com.ticketrush.ticketrush.domain.event.entity;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 구역(등급) (db-schema.md 3번).
 * SEATED면 rowCount/seatsPerRow만 쓰고, STANDING이면 totalQuantity만 쓴다.
 * 두 컬럼 세트가 동시에 채워지지 않도록 하는 검증은 애플리케이션(EventService)이 담당한다.
 */
@Getter
@Entity
@Table(name = "section")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Section extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(nullable = false, length = 50)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SectionType type;

    @Column(nullable = false)
    private int price;

    private Integer rowCount;

    private Integer seatsPerRow;

    /** 실시간 잔여 수량의 원천은 Redis이고, 이 값은 rebuild 시 재계산 기준이 된다(db-schema.md 3번). */
    private Integer totalQuantity;

    private Section(Event event, String name, SectionType type, int price,
                    Integer rowCount, Integer seatsPerRow, Integer totalQuantity) {
        this.event = event;
        this.name = name;
        this.type = type;
        this.price = price;
        this.rowCount = rowCount;
        this.seatsPerRow = seatsPerRow;
        this.totalQuantity = totalQuantity;
    }

    public static Section seated(Event event, String name, int price, int rowCount, int seatsPerRow) {
        return new Section(event, name, SectionType.SEATED, price, rowCount, seatsPerRow, null);
    }

    public static Section standing(Event event, String name, int price, int totalQuantity) {
        return new Section(event, name, SectionType.STANDING, price, null, null, totalQuantity);
    }

    public boolean isStanding() {
        return type == SectionType.STANDING;
    }

    /** 이 구역에서 만들어질 좌석 개수. STANDING이면 개별 좌석이 없으므로 0. */
    public int seatCount() {
        return isStanding() ? 0 : rowCount * seatsPerRow;
    }
}
