package com.ticketrush.ticketrush.domain.event.service;

import com.ticketrush.ticketrush.domain.account.entity.Account;
import com.ticketrush.ticketrush.domain.account.repository.AccountRepository;
import com.ticketrush.ticketrush.domain.event.dto.EventRequest;
import com.ticketrush.ticketrush.domain.event.dto.EventResponse;
import com.ticketrush.ticketrush.domain.event.dto.EventSummaryResponse;
import com.ticketrush.ticketrush.domain.event.dto.SectionRequest;
import com.ticketrush.ticketrush.domain.event.entity.Event;
import com.ticketrush.ticketrush.domain.event.entity.Section;
import com.ticketrush.ticketrush.domain.event.entity.SectionType;
import com.ticketrush.ticketrush.domain.event.repository.EventRepository;
import com.ticketrush.ticketrush.domain.event.repository.SeatBulkInsertRepository;
import com.ticketrush.ticketrush.domain.event.repository.SectionRepository;
import com.ticketrush.ticketrush.domain.event.repository.SeatRepository;
import com.ticketrush.ticketrush.domain.seat.repository.SeatCatalogRepository;
import com.ticketrush.ticketrush.domain.seat.repository.SeatStatusRepository;
import com.ticketrush.ticketrush.domain.seat.service.SeatStatusRebuildService;
import com.ticketrush.ticketrush.global.exception.BusinessException;
import com.ticketrush.ticketrush.global.exception.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이벤트/구역/좌석 등록 (api-design.md 2번, decisions.md 12번).
 *
 * 수정/삭제는 예매 시작(openAt) 전에만 허용한다(사용자 확인 완료) — 판매가 시작된 뒤 좌석을 바꾸면
 * 이미 팔린 좌석의 예약 기록, Redis 좌석 상태와 어긋나기 때문이다.
 */
@Service
@RequiredArgsConstructor
public class EventService {

    /**
     * 이벤트 전체 좌석 수 상한 (사용자 확인 완료).
     * 국내 최대 공연장인 잠실올림픽주경기장이 약 69,000석이라, 그보다 조금 여유 있는 70,000으로 잡았다.
     * 목적은 "이 공연은 몇 석까지"라는 비즈니스 규칙이 아니라, 3,000석을 입력하려다 0을 더 눌러
     * 300,000석을 보내는 실수나 악의적 요청이 서버를 멈추게 하는 것을 막는 것이다
     * (공연 규모 자체는 주최자가 입력하는 값이므로 이벤트마다 다른 상한을 둘 이유가 없다).
     */
    private static final int MAX_TOTAL_SEATS = 70_000;

    /**
     * 구역 개수 상한 (잠정값).
     * 좌석 총합만 막으면 "1석짜리 구역 70,000개" 같은 요청이 통과하는데, 구역은 좌석과 달리
     * JPA로 저장해 배치가 걸리지 않으므로 INSERT가 구역 수만큼 낱개로 나간다.
     *
     * 실제 공연장은 구역이 생각보다 잘게 나뉜다 — KSPO DOME(체조경기장)만 해도 1층이 43구역이고
     * 플로어·2층까지 더하면 50을 넘는다. 우리 모델의 구역은 "가격 + 사각 격자" 단위라
     * 물리적으로 떨어진 구역을 하나로 합칠 수 없어 실제 구역 수만큼 등록해야 한다.
     * 정확한 값은 3주차 부하 테스트에서 조정하고, 지금은 현실적인 시나리오를 막지 않을 만큼만 열어둔다.
     */
    private static final int MAX_SECTIONS = 200;

    private final EventRepository eventRepository;
    private final SectionRepository sectionRepository;
    private final SeatRepository seatRepository;
    private final SeatBulkInsertRepository seatBulkInsertRepository;
    private final SeatCatalogRepository seatCatalogRepository;
    private final SeatStatusRepository seatStatusRepository;
    private final SeatStatusRebuildService seatStatusRebuildService;
    private final AccountRepository accountRepository;

    @Transactional
    public EventResponse register(Long organizerId, EventRequest request) {
        Account organizer = accountRepository.findById(organizerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));

        Event event = eventRepository.save(Event.create(organizer, request.name(), request.openAt()));
        List<Section> sections = createSections(event, request.sections());

        return buildResponse(event, sections);
    }

    /** 전체 교체. 기존 구역/좌석을 모두 지우고 요청 내용으로 새로 만든다. */
    @Transactional
    public EventResponse update(Long organizerId, Long eventId, EventRequest request) {
        Event event = findModifiableEvent(organizerId, eventId);

        deleteSectionsAndSeats(eventId);
        event.update(request.name(), request.openAt());
        List<Section> sections = createSections(event, request.sections());

        return buildResponse(event, sections);
    }

    @Transactional
    public void delete(Long organizerId, Long eventId) {
        Event event = findModifiableEvent(organizerId, eventId);

        deleteSectionsAndSeats(eventId);
        eventRepository.delete(event);
        seatStatusRepository.delete(eventId);
    }

    @Transactional(readOnly = true)
    public List<EventSummaryResponse> findAll() {
        return eventRepository.findAllByOrderByOpenAtAsc().stream()
                .map(EventSummaryResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public EventResponse findById(Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND));
        List<Section> sections = sectionRepository.findAllByEventIdOrderByIdAsc(eventId);
        seatStatusRebuildService.ensureFresh(eventId);

        // 조회 시에는 스탠딩 잔여 수량을 Redis 실시간 값으로 채운다.
        List<Long> standingSectionIds = sections.stream()
                .filter(Section::isStanding)
                .map(Section::getId)
                .toList();
        Map<Long, Integer> standingRemaining =
                seatStatusRepository.findStandingRemaining(eventId, standingSectionIds);

        return EventResponse.of(event, sections, standingRemaining);
    }

    /** 수정/삭제 공통 전제: 존재 + 본인 소유 + 아직 오픈 전. */
    private Event findModifiableEvent(Long organizerId, Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND));
        if (!event.isOwnedBy(organizerId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "본인이 등록한 이벤트만 수정/삭제할 수 있습니다.");
        }
        if (event.isOpened()) {
            throw new BusinessException(ErrorCode.EVENT_ALREADY_OPENED);
        }
        return event;
    }

    private void deleteSectionsAndSeats(Long eventId) {
        sectionRepository.findAllByEventIdOrderByIdAsc(eventId).stream()
                .filter(section -> !section.isStanding())
                .forEach(section -> seatCatalogRepository.delete(section.getId()));
        seatRepository.deleteAllByEventId(eventId);
        sectionRepository.deleteAllByEventId(eventId);
    }

    private List<Section> createSections(Event event, List<SectionRequest> requests) {
        validateSections(requests);

        List<Section> sections = sectionRepository.saveAll(
                requests.stream().map(request -> toSection(event, request)).toList());

        // 구역 행이 DB에 들어간 뒤라야 seat의 FK(section_id)를 채울 수 있다.
        sections.stream()
                .filter(section -> !section.isStanding())
                .forEach(section -> {
                    seatBulkInsertRepository.insertGrid(
                            section.getId(), section.getRowCount(), section.getSeatsPerRow());
                    cacheSeatCatalog(section.getId());
                });

        initializeSeatStatus(event.getId(), sections);
        return sections;
    }

    /**
     * 방금 만든 좌석을 DB에서 딱 한 번 읽어 Redis 캐시(SeatCatalogRepository)에 채운다 —
     * 좌석 조회 API가 그 뒤로는 DB를 전혀 안 치게 하기 위함(위 클래스 주석 참고).
     */
    private void cacheSeatCatalog(Long sectionId) {
        List<SeatCatalogRepository.Entry> entries = seatRepository
                .findAllBySectionIdOrderByRowNoAscSeatNoAsc(sectionId).stream()
                .map(seat -> new SeatCatalogRepository.Entry(seat.getId(), seat.getRowNo(), seat.getSeatNo()))
                .toList();
        seatCatalogRepository.save(sectionId, entries);
    }

    /**
     * Redis 좌석 상태 Hash 초기화. 트랜잭션 안에서 마지막에 수행하므로,
     * 실패하면 DB 저장까지 통째로 롤백되어 "DB에는 있는데 Redis에는 없는" 이벤트가 남지 않는다
     * (decisions.md 1번이 조회 시점의 lazy 초기화를 금지하고 있어, 등록 시점에 확실히 만들어야 한다).
     */
    private void initializeSeatStatus(Long eventId, List<Section> sections) {
        Map<Long, Integer> standingQuantities = new LinkedHashMap<>();
        sections.stream()
                .filter(Section::isStanding)
                .forEach(section -> standingQuantities.put(section.getId(), section.getTotalQuantity()));

        seatStatusRepository.delete(eventId);
        seatStatusRepository.initialize(eventId, standingQuantities);
    }

    /** 구역별 필드 조합 검증 + 이벤트 전체 규모 상한 검증. 규모 계산은 필드 검증이 끝난 뒤에 해야 한다. */
    private void validateSections(List<SectionRequest> requests) {
        if (requests.size() > MAX_SECTIONS) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "구역은 최대 %d개까지 등록할 수 있습니다. (요청: %d개)"
                            .formatted(MAX_SECTIONS, requests.size()));
        }

        long totalSeats = 0;
        for (SectionRequest request : requests) {
            validateFields(request);
            long seatCount = seatCountOf(request);

            // 구역 하나만으로 이미 상한을 넘으면 합계를 내기 전에 거절한다.
            // 이 검사가 없으면 합계 쪽에서 long이 다시 넘칠 수 있다 —
            // 구역 하나의 최대치가 약 460경(21억 x 21억)이라 3개만 모여도 long(약 922경)을 초과한다.
            // 여기서 걸러내면 이후 합계는 최대 (구역 50개 x 70,000)이라 넘칠 수 없다.
            if (seatCount > MAX_TOTAL_SEATS) {
                throw exceededSeatLimit(seatCount);
            }
            totalSeats += seatCount;
        }

        if (totalSeats > MAX_TOTAL_SEATS) {
            throw exceededSeatLimit(totalSeats);
        }
    }

    private BusinessException exceededSeatLimit(long requestedSeats) {
        return new BusinessException(ErrorCode.INVALID_INPUT,
                "이벤트 전체 좌석 수는 %,d석을 넘을 수 없습니다. (요청: %,d석)"
                        .formatted(MAX_TOTAL_SEATS, requestedSeats));
    }

    private void validateFields(SectionRequest request) {
        if (request.type() == SectionType.SEATED) {
            requireNull(request.totalQuantity(), "지정석 구역에는 totalQuantity를 지정할 수 없습니다.");
            requireNotNull(request.rowCount(), "지정석 구역은 rowCount가 필요합니다.");
            requireNotNull(request.seatsPerRow(), "지정석 구역은 seatsPerRow가 필요합니다.");
            return;
        }
        requireNull(request.rowCount(), "스탠딩 구역에는 rowCount를 지정할 수 없습니다.");
        requireNull(request.seatsPerRow(), "스탠딩 구역에는 seatsPerRow를 지정할 수 없습니다.");
        requireNotNull(request.totalQuantity(), "스탠딩 구역은 totalQuantity가 필요합니다.");
    }

    /**
     * 반드시 long으로 계산한다 — int로 곱하면 오버플로가 나서
     * (예: 50,000 x 50,000 = 25억 > int 최대값) 오히려 상한 검사를 통과해버린다.
     */
    private long seatCountOf(SectionRequest request) {
        if (request.type() == SectionType.SEATED) {
            return (long) request.rowCount() * request.seatsPerRow();
        }
        return request.totalQuantity();
    }

    private Section toSection(Event event, SectionRequest request) {
        if (request.type() == SectionType.SEATED) {
            return Section.seated(event, request.name(), request.price(),
                    request.rowCount(), request.seatsPerRow());
        }
        return Section.standing(event, request.name(), request.price(), request.totalQuantity());
    }

    private void requireNull(Object value, String message) {
        if (value != null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, message);
        }
    }

    private void requireNotNull(Object value, String message) {
        if (value == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, message);
        }
    }

    /** 등록/수정 직후 응답: 방금 만든 값이라 Redis를 다시 읽지 않고 그대로 사용한다. */
    private EventResponse buildResponse(Event event, List<Section> sections) {
        Map<Long, Integer> standingRemaining = new LinkedHashMap<>();
        sections.stream()
                .filter(Section::isStanding)
                .forEach(section -> standingRemaining.put(section.getId(), section.getTotalQuantity()));
        return EventResponse.of(event, sections, standingRemaining);
    }
}
