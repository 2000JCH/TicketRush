package com.ticketrush.ticketrush.domain.event.repository;

import com.ticketrush.ticketrush.domain.event.entity.Section;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SectionRepository extends JpaRepository<Section, Long> {

    List<Section> findAllByEventIdOrderByIdAsc(Long eventId);

    /** 오픈 전 전체 교체/삭제 시 사용. 좌석을 먼저 지운 뒤 호출해야 한다(FK). */
    @Modifying
    @Query("DELETE FROM Section s WHERE s.event.id = :eventId")
    void deleteAllByEventId(@Param("eventId") Long eventId);
}
