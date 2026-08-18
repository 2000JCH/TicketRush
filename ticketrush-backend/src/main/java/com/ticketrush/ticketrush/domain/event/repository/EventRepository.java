package com.ticketrush.ticketrush.domain.event.repository;

import com.ticketrush.ticketrush.domain.event.entity.Event;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<Event, Long> {

    /** 목록은 오픈 시각순 (api-design.md 2번). */
    List<Event> findAllByOrderByOpenAtAsc();

    /** 이미 오픈한 이벤트. 대기열 Scheduler가 입장시킬 대상을 고르는 데 쓴다. */
    List<Event> findAllByOpenAtLessThanEqual(LocalDateTime now);
}
