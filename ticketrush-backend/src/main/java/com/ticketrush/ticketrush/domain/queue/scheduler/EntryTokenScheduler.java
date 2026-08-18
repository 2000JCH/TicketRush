package com.ticketrush.ticketrush.domain.queue.scheduler;

import com.ticketrush.ticketrush.domain.event.entity.Event;
import com.ticketrush.ticketrush.domain.event.repository.EventRepository;
import com.ticketrush.ticketrush.domain.queue.service.QueueService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 대기열 상위 N명에게 주기적으로 입장 토큰을 발급한다 (decisions.md 4번).
 *
 * 아직 오픈 전인 이벤트는 처리하지 않는다 — 대기열에는 오픈 전부터 줄을 설 수 있지만,
 * 오픈 전에 입장 토큰을 주면 예매 시작 시각 자체가 무의미해지기 때문이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EntryTokenScheduler {

    private final EventRepository eventRepository;
    private final QueueService queueService;

    @Scheduled(fixedDelayString = "${queue.admit-interval-millis}")
    public void admit() {
        List<Event> openedEvents = eventRepository.findAllByOpenAtLessThanEqual(LocalDateTime.now());

        for (Event event : openedEvents) {
            try {
                queueService.admitFront(event.getId());
            } catch (Exception e) {
                // 한 이벤트에서 실패해도 나머지 이벤트의 대기열은 계속 처리되어야 한다.
                log.error("이벤트 {} 대기열 입장 처리 실패", event.getId(), e);
            }
        }
    }
}
