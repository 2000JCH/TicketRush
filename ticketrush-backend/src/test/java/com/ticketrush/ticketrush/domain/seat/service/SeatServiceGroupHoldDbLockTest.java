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
import com.ticketrush.ticketrush.domain.seat.dto.SeatStatusResponse;
import com.ticketrush.ticketrush.domain.seat.entity.SeatState;
import com.ticketrush.ticketrush.global.exception.BusinessException;
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
 * {@link SeatServiceGroupHoldTest}와 같은 그룹 홀드 시나리오를 `group-hold.lock-strategy=db`로
 * 재검증한다 — DB 비관적 락(`SELECT ... FOR UPDATE`) 구현도 오버셀 0건을 보장하는지가 핵심이다
 * (decisions.md 2번 채택 기준의 최우선 전제조건). 두 파일이 겹치는 헬퍼가 있지만, 프로퍼티가 다른
 * 별도 스프링 컨텍스트로 떠야 해서 테스트 클래스 자체는 분리했다.
 */
@SpringBootTest(properties = "group-hold.lock-strategy=db")
class SeatServiceGroupHoldDbLockTest {

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

        var response = seatService.hold(buyer.getId(), eventId, entryToken,
                new SeatHoldRequest(sectionId, seatIds, null));

        assertThat(response.holdExpiresAt()).isNotNull();
        assertThat(seatStatusesOf(buyer, eventId, sectionId, seatIds)).containsOnly(SeatState.HELD);
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
                .isInstanceOf(BusinessException.class);

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
                "그룹홀드 DB락 테스트 콘서트",
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
