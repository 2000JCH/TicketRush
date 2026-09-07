package com.ticketrush.ticketrush.domain.event.service;

import com.ticketrush.ticketrush.domain.event.dto.AdminEventStatsResponse;
import com.ticketrush.ticketrush.domain.event.entity.Event;
import com.ticketrush.ticketrush.domain.event.repository.EventRepository;
import com.ticketrush.ticketrush.domain.event.repository.SeatRepository;
import com.ticketrush.ticketrush.domain.event.repository.SectionRepository;
import com.ticketrush.ticketrush.domain.reservation.repository.ReservationRepository;
import com.ticketrush.ticketrush.domain.reservation.repository.ReservationRepository.EventSalesRow;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 콘서트 현황 (판매 금액 + 좌석 판매현황). 이벤트별로 5개 집계 쿼리 결과를 메모리에서
 * 합친다 — 이벤트 수만큼 쿼리를 반복하지 않도록 전부 GROUP BY로 한 번에 가져온다.
 */
@Service
@RequiredArgsConstructor
public class AdminEventStatsService {

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final SectionRepository sectionRepository;
    private final ReservationRepository reservationRepository;

    @Transactional(readOnly = true)
    public List<AdminEventStatsResponse> findAll() {
        Map<Long, Long> seatedCapacity = new HashMap<>();
        seatRepository.countSeatsPerEvent()
                .forEach(row -> seatedCapacity.put(row.getEventId(), row.getSeatCount()));

        Map<Long, Long> standingCapacity = new HashMap<>();
        sectionRepository.sumStandingCapacityPerEvent()
                .forEach(row -> standingCapacity.put(row.getEventId(), row.getQuantity()));

        Map<Long, EventSalesRow> sales = new HashMap<>();
        reservationRepository.aggregateConfirmedSalesByEvent()
                .forEach(row -> sales.put(row.getEventId(), row));

        return eventRepository.findAllByOrderByOpenAtAsc().stream()
                .map(event -> toStats(event, seatedCapacity, standingCapacity, sales))
                .toList();
    }

    private AdminEventStatsResponse toStats(
            Event event,
            Map<Long, Long> seatedCapacity,
            Map<Long, Long> standingCapacity,
            Map<Long, EventSalesRow> sales) {
        Long id = event.getId();
        long capacity = seatedCapacity.getOrDefault(id, 0L) + standingCapacity.getOrDefault(id, 0L);
        EventSalesRow row = sales.get(id);
        long sold = row != null ? row.getTickets() : 0L;
        return new AdminEventStatsResponse(
                id,
                event.getName(),
                event.getOpenAt(),
                capacity,
                sold,
                Math.max(0L, capacity - sold),
                row != null ? row.getReservations() : 0L,
                row != null ? row.getAmount() : 0L);
    }
}
