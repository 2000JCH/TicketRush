package com.ticketrush.ticketrush.domain.seat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketrush.ticketrush.domain.account.entity.Account;
import com.ticketrush.ticketrush.domain.account.entity.Role;
import com.ticketrush.ticketrush.domain.account.repository.AccountRepository;
import com.ticketrush.ticketrush.domain.event.dto.EventRequest;
import com.ticketrush.ticketrush.domain.event.dto.EventResponse;
import com.ticketrush.ticketrush.domain.event.dto.SectionRequest;
import com.ticketrush.ticketrush.domain.event.entity.Seat;
import com.ticketrush.ticketrush.domain.event.entity.SectionType;
import com.ticketrush.ticketrush.domain.event.repository.SeatRepository;
import com.ticketrush.ticketrush.domain.event.service.EventService;
import com.ticketrush.ticketrush.domain.queue.repository.EntryTokenRepository;
import com.ticketrush.ticketrush.domain.seat.dto.SeatHoldRequest;
import com.ticketrush.ticketrush.domain.seat.dto.SeatHoldResponse;
import com.ticketrush.ticketrush.domain.seat.dto.SeatStatusResponse;
import com.ticketrush.ticketrush.domain.seat.entity.SeatState;
import com.ticketrush.ticketrush.global.exception.BusinessException;
import com.ticketrush.ticketrush.global.exception.ErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 그룹 좌석 홀드(2매 동시 선택, decisions.md 2번) 검증. 기본 프로퍼티(group-hold.lock-strategy=redis,
 * application.properties 기본값) 그대로 돌려 Redisson RLock 구현을 검증한다 — DB 비관적 락 구현은
 * {@link SeatServiceGroupHoldDbLockTest}가 같은 핵심 시나리오를 다른 프로퍼티로 재검증한다.
 */
@SpringBootTest
class SeatServiceGroupHoldTest {

    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private EventService eventService;
    @Autowired
    private SeatRepository seatRepository;
    @Autowired
    private EntryTokenRepository entryTokenRepository;
    @Autowired
    private SeatService seatService;

    private Account organizer;

    @BeforeEach
    void setUp() {
        organizer = accountRepository.save(
                Account.signUp("organizer_" + UUID.randomUUID() + "@test.com", "encoded", Role.ORGANIZER));
    }

    @Test
    void groupHold_bothSeatsSucceed() {
        EventResponse event = createEvent();
        Long eventId = event.id();
        Long sectionId = seatedSectionId(event);
        List<Long> seatIds = firstSeatIds(sectionId, 2);
        Account buyer = createBuyer();
        String entryToken = entryTokenRepository.issue(eventId, buyer.getId());

        SeatHoldResponse response = seatService.hold(buyer.getId(), eventId, entryToken,
                new SeatHoldRequest(sectionId, seatIds, null));

        assertThat(response.holdExpiresAt()).isNotNull();
        assertThat(seatStatusesOf(buyer, eventId, sectionId, seatIds)).containsOnly(SeatState.HELD);
    }

    @Test
    void groupHold_duplicateSeatIds_rejected() {
        EventResponse event = createEvent();
        Long eventId = event.id();
        Long sectionId = seatedSectionId(event);
        Long seatId = firstSeatIds(sectionId, 1).get(0);
        Account buyer = createBuyer();
        String entryToken = entryTokenRepository.issue(eventId, buyer.getId());

        assertThatThrownBy(() -> seatService.hold(buyer.getId(), eventId, entryToken,
                new SeatHoldRequest(sectionId, List.of(seatId, seatId), null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    void groupHold_moreThanTwoSeats_rejected() {
        EventResponse event = createEvent();
        Long eventId = event.id();
        Long sectionId = seatedSectionId(event);
        List<Long> seatIds = firstSeatIds(sectionId, 3);
        Account buyer = createBuyer();
        String entryToken = entryTokenRepository.issue(eventId, buyer.getId());

        assertThatThrownBy(() -> seatService.hold(buyer.getId(), eventId, entryToken,
                new SeatHoldRequest(sectionId, seatIds, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    void groupHold_oneSeatAlreadyHeld_rollsBackTheOther() {
        EventResponse event = createEvent();
        Long eventId = event.id();
        Long sectionId = seatedSectionId(event);
        List<Long> seatIds = firstSeatIds(sectionId, 2);
        Long freeSeatId = seatIds.get(0);
        Long takenSeatId = seatIds.get(1);

        Account otherBuyer = createBuyer();
        String otherToken = entryTokenRepository.issue(eventId, otherBuyer.getId());
        seatService.hold(otherBuyer.getId(), eventId, otherToken,
                new SeatHoldRequest(sectionId, List.of(takenSeatId), null));

        Account buyer = createBuyer();
        String entryToken = entryTokenRepository.issue(eventId, buyer.getId());

        assertThatThrownBy(() -> seatService.hold(buyer.getId(), eventId, entryToken,
                new SeatHoldRequest(sectionId, seatIds, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SEAT_ALREADY_HELD);

        // 먼저 성공했을 수 있는 freeSeatId까지 되돌아가야 한다 — 전부 성공 또는 전부 실패.
        assertThat(seatStatusesOf(buyer, eventId, sectionId, List.of(freeSeatId))).containsOnly(SeatState.AVAILABLE);
        assertThat(seatService.findActiveHold(eventId, buyer.getId())).isEmpty();
    }

    @Test
    void groupHold_concurrentSamePair_onlyOneSucceeds() throws InterruptedException {
        EventResponse event = createEvent();
        Long eventId = event.id();
        Long sectionId = seatedSectionId(event);
        List<Long> seatIds = firstSeatIds(sectionId, 2);

        int attempts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();

        for (int i = 0; i < attempts; i++) {
            Account buyer = createBuyer();
            String entryToken = entryTokenRepository.issue(eventId, buyer.getId());
            pool.submit(() -> {
                try {
                    start.await();
                    seatService.hold(buyer.getId(), eventId, entryToken,
                            new SeatHoldRequest(sectionId, seatIds, null));
                    succeeded.incrementAndGet();
                } catch (BusinessException | InterruptedException ignored) {
                    // 나머지는 실패해야 정상 — 오버셀 0건 검증이 이 테스트의 목적이다.
                }
            });
        }

        start.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(succeeded.get()).isEqualTo(1);
    }

    private EventResponse createEvent() {
        EventRequest request = new EventRequest(
                "그룹홀드 테스트 콘서트",
                LocalDateTime.now().plusSeconds(30),
                List.of(new SectionRequest("SEATED-TEST", SectionType.SEATED, 10000, 1, 5, null)));
        return eventService.register(organizer.getId(), request);
    }

    private Account createBuyer() {
        return accountRepository.save(
                Account.signUp("buyer_" + UUID.randomUUID() + "@test.com", "encoded", Role.BUYER));
    }

    private Long seatedSectionId(EventResponse event) {
        return event.sections().stream()
                .filter(s -> s.type() == SectionType.SEATED).findFirst().orElseThrow().id();
    }

    private List<Long> firstSeatIds(Long sectionId, int count) {
        return seatRepository.findAllBySectionIdOrderByRowNoAscSeatNoAsc(sectionId).stream()
                .map(Seat::getId)
                .limit(count)
                .toList();
    }

    private List<SeatState> seatStatusesOf(Account buyer, Long eventId, Long sectionId, List<Long> seatIds) {
        String entryToken = entryTokenRepository.issue(eventId, buyer.getId());
        return seatService.findStatuses(buyer.getId(), eventId, sectionId, entryToken).stream()
                .filter(s -> seatIds.contains(s.seatId()))
                .map(SeatStatusResponse::status)
                .toList();
    }
}
