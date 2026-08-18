package com.ticketrush.ticketrush.domain.event.repository;

import com.ticketrush.ticketrush.domain.event.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    long countBySectionId(Long sectionId);

    /**
     * 오픈 전 전체 교체/삭제 시 이벤트에 속한 좌석을 한 번에 지운다.
     * 좌석이 수천 개일 수 있어 엔티티를 하나씩 지우지 않고 DELETE 한 문장으로 처리한다.
     */
    @Modifying
    @Query("DELETE FROM Seat s WHERE s.section.id IN "
            + "(SELECT sec.id FROM Section sec WHERE sec.event.id = :eventId)")
    void deleteAllByEventId(@Param("eventId") Long eventId);
}
