package com.ticketrush.ticketrush.domain.event.repository;

import com.ticketrush.ticketrush.domain.event.entity.Section;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SectionRepository extends JpaRepository<Section, Long> {

    List<Section> findAllByEventIdOrderByIdAsc(Long eventId);

    /** 관리자 콘서트 현황 — 이벤트별 스탠딩 총 수용 인원(SEATED 구역은 좌석 수로 따로 센다). */
    @Query("SELECT sec.event.id AS eventId, COALESCE(SUM(sec.totalQuantity), 0) AS quantity FROM Section sec "
            + "WHERE sec.type = com.ticketrush.ticketrush.domain.event.entity.SectionType.STANDING "
            + "GROUP BY sec.event.id")
    List<EventStandingCapacityRow> sumStandingCapacityPerEvent();

    interface EventStandingCapacityRow {
        Long getEventId();
        long getQuantity();
    }

    /** 오픈 전 전체 교체/삭제 시 사용. 좌석을 먼저 지운 뒤 호출해야 한다(FK). */
    @Modifying
    @Query("DELETE FROM Section s WHERE s.event.id = :eventId")
    void deleteAllByEventId(@Param("eventId") Long eventId);
}
