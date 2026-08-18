package com.ticketrush.ticketrush.domain.event.entity;

import com.ticketrush.ticketrush.domain.account.entity.Account;
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
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 콘서트(이벤트) (db-schema.md 2번).
 * openAt은 선착순 오픈 시각으로, 대기열 스케줄러가 이 시각을 기준으로 동작한다(decisions.md 4번).
 */
@Getter
@Entity
@Table(name = "event")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Event extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organizer_id", nullable = false)
    private Account organizer;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false)
    private LocalDateTime openAt;

    private Event(Account organizer, String name, LocalDateTime openAt) {
        this.organizer = organizer;
        this.name = name;
        this.openAt = openAt;
    }

    public static Event create(Account organizer, String name, LocalDateTime openAt) {
        return new Event(organizer, name, openAt);
    }

    public void update(String name, LocalDateTime openAt) {
        this.name = name;
        this.openAt = openAt;
    }

    /** 예매가 시작된 뒤에는 구역/좌석을 바꿀 수 없다(사용자 확인 완료) — 이미 팔린 좌석과 어긋나기 때문. */
    public boolean isOpened() {
        return !LocalDateTime.now().isBefore(openAt);
    }

    /** 다른 ORGANIZER가 남의 이벤트를 수정하지 못하게 막는다. */
    public boolean isOwnedBy(Long accountId) {
        return organizer.getId().equals(accountId);
    }
}
