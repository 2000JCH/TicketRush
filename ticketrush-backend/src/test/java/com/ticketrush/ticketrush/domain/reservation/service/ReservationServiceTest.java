package com.ticketrush.ticketrush.domain.reservation.service;

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
import com.ticketrush.ticketrush.domain.reservation.dto.PaymentRequest;
import com.ticketrush.ticketrush.domain.reservation.dto.ReservationResponse;
import com.ticketrush.ticketrush.domain.reservation.entity.Reservation;
import com.ticketrush.ticketrush.domain.reservation.entity.ReservationSeat;
import com.ticketrush.ticketrush.domain.reservation.entity.ReservationStatus;
import com.ticketrush.ticketrush.domain.reservation.repository.ReservationRepository;
import com.ticketrush.ticketrush.domain.reservation.repository.ReservationSeatRepository;
import com.ticketrush.ticketrush.domain.seat.dto.SeatHoldRequest;
import com.ticketrush.ticketrush.domain.seat.entity.SeatState;
import com.ticketrush.ticketrush.domain.seat.service.SeatService;
import com.ticketrush.ticketrush.global.exception.BusinessException;
import com.ticketrush.ticketrush.global.exception.ErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Saga 상태머신 검증(decisions.md 5번). 실제 PG 웹훅이 아직 없어(사용자 확인 완료)
 * confirmPayment/markPaymentFailed/releaseAfterFailure를 직접 호출하는 자동 테스트로
 * 상태 전이와 Saga 보상(좌석 반납)을 검증한다. 이 프로젝트의 첫 자동 테스트 도입 대상 도메인이다.
 */
@SpringBootTest
class ReservationServiceTest {

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
    @Autowired
    private ReservationService reservationService;
    @Autowired
    private ReservationRepository reservationRepository;
    @Autowired
    private ReservationSeatRepository reservationSeatRepository;

    private Account organizer;
    private Account buyer;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        organizer = accountRepository.save(
                Account.signUp("organizer_" + suffix + "@test.com", "encoded", Role.ORGANIZER));
        buyer = accountRepository.save(Account.signUp("buyer_" + suffix + "@test.com", "encoded", Role.BUYER));
    }

    @Test
    void requestPayment_seat_createsReservationAndReservationSeat() {
        EventResponse event = createEventWithSeatedAndStanding();
        Long eventId = event.id();
        Long sectionId = seatedSectionId(event);
        Long seatId = firstSeatId(sectionId);
        String entryToken = entryTokenRepository.issue(eventId, buyer.getId());

        seatService.hold(buyer.getId(), eventId, entryToken,
                new SeatHoldRequest(sectionId, List.of(seatId), null));

        String idempotencyKey = UUID.randomUUID().toString();
        ReservationResponse response = reservationService.requestPayment(buyer.getId(), entryToken,
                new PaymentRequest(eventId, sectionId, List.of(seatId), null, idempotencyKey));

        assertThat(response.status()).isEqualTo("PAYMENT_REQUESTED");

        Reservation reservation = reservationRepository.findById(response.reservationId()).orElseThrow();
        assertThat(reservation.getQuantity()).isEqualTo(1);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PAYMENT_REQUESTED);

        List<ReservationSeat> seats = reservationSeatRepository.findAllByReservationId(reservation.getId());
        assertThat(seats).hasSize(1);
        assertThat(seats.get(0).getSeat().getId()).isEqualTo(seatId);
        assertThat(seats.get(0).getStatus()).isEqualTo(ReservationStatus.PAYMENT_REQUESTED);
    }

    @Test
    void requestPayment_duplicateIdempotencyKey_rejected() {
        EventResponse event = createEventWithSeatedAndStanding();
        Long eventId = event.id();
        Long sectionId = seatedSectionId(event);
        Long seatId = firstSeatId(sectionId);
        String entryToken = entryTokenRepository.issue(eventId, buyer.getId());
        seatService.hold(buyer.getId(), eventId, entryToken,
                new SeatHoldRequest(sectionId, List.of(seatId), null));

        String idempotencyKey = UUID.randomUUID().toString();
        PaymentRequest request = new PaymentRequest(eventId, sectionId, List.of(seatId), null, idempotencyKey);
        reservationService.requestPayment(buyer.getId(), entryToken, request);

        assertThatThrownBy(() -> reservationService.requestPayment(buyer.getId(), entryToken, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_PAYMENT_REQUEST);
    }

    @Test
    void requestPayment_withoutActiveHold_rejected() {
        EventResponse event = createEventWithSeatedAndStanding();
        Long eventId = event.id();
        Long sectionId = seatedSectionId(event);
        Long seatId = firstSeatId(sectionId);
        String entryToken = entryTokenRepository.issue(eventId, buyer.getId());

        PaymentRequest request = new PaymentRequest(
                eventId, sectionId, List.of(seatId), null, UUID.randomUUID().toString());

        assertThatThrownBy(() -> reservationService.requestPayment(buyer.getId(), entryToken, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACTIVE_HOLD_NOT_FOUND);
    }

    @Test
    void requestPayment_seatIdMismatchWithHold_rejected() {
        EventResponse event = createEventWithSeatedAndStanding();
        Long eventId = event.id();
        Long sectionId = seatedSectionId(event);
        List<Seat> seats = seatRepository.findAllBySectionIdOrderByRowNoAscSeatNoAsc(sectionId);
        Long heldSeatId = seats.get(0).getId();
        Long otherSeatId = seats.get(1).getId();
        String entryToken = entryTokenRepository.issue(eventId, buyer.getId());
        seatService.hold(buyer.getId(), eventId, entryToken,
                new SeatHoldRequest(sectionId, List.of(heldSeatId), null));

        PaymentRequest request = new PaymentRequest(
                eventId, sectionId, List.of(otherSeatId), null, UUID.randomUUID().toString());

        assertThatThrownBy(() -> reservationService.requestPayment(buyer.getId(), entryToken, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    void confirmPayment_transitionsToConfirmed_andClearsActiveHold() {
        EventResponse event = createEventWithSeatedAndStanding();
        Long eventId = event.id();
        Long sectionId = seatedSectionId(event);
        Long seatId = firstSeatId(sectionId);
        String entryToken = entryTokenRepository.issue(eventId, buyer.getId());
        seatService.hold(buyer.getId(), eventId, entryToken,
                new SeatHoldRequest(sectionId, List.of(seatId), null));
        ReservationResponse response = reservationService.requestPayment(buyer.getId(), entryToken,
                new PaymentRequest(eventId, sectionId, List.of(seatId), null, UUID.randomUUID().toString()));

        reservationService.confirmPayment(response.reservationId());

        Reservation reservation = reservationRepository.findById(response.reservationId()).orElseThrow();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PAYMENT_CONFIRMED);
        assertThat(reservation.getConfirmedAt()).isNotNull();

        List<ReservationSeat> seats = reservationSeatRepository.findAllByReservationId(reservation.getId());
        assertThat(seats.get(0).getStatus()).isEqualTo(ReservationStatus.PAYMENT_CONFIRMED);

        // 결제 확정 후에는 다음 시도를 위해 active_reservation이 정리되어야 한다.
        assertThat(seatService.findActiveHold(eventId, buyer.getId())).isEmpty();

        // 확정된 좌석은 seat_status Hash에 그대로 HELD로 남아 "확정 판매"를 나타낸다(redis-design.md 3번).
        assertThat(seatStatusOf(eventId, seatId)).isEqualTo(SeatState.HELD);
    }

    @Test
    void confirmPayment_isIdempotent() {
        EventResponse event = createEventWithSeatedAndStanding();
        Long eventId = event.id();
        Long sectionId = seatedSectionId(event);
        Long seatId = firstSeatId(sectionId);
        String entryToken = entryTokenRepository.issue(eventId, buyer.getId());
        seatService.hold(buyer.getId(), eventId, entryToken,
                new SeatHoldRequest(sectionId, List.of(seatId), null));
        ReservationResponse response = reservationService.requestPayment(buyer.getId(), entryToken,
                new PaymentRequest(eventId, sectionId, List.of(seatId), null, UUID.randomUUID().toString()));

        reservationService.confirmPayment(response.reservationId());
        LocalDateTime firstConfirmedAt =
                reservationRepository.findById(response.reservationId()).orElseThrow().getConfirmedAt();

        // 웹훅이 중복 수신되어도(decisions.md 5번) 이미 처리된 요청이면 조용히 무시해야 한다.
        reservationService.confirmPayment(response.reservationId());
        Reservation reservation = reservationRepository.findById(response.reservationId()).orElseThrow();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PAYMENT_CONFIRMED);
        assertThat(reservation.getConfirmedAt()).isEqualTo(firstConfirmedAt);
    }

    @Test
    void failPayment_compensatesAndReleasesSeat() {
        EventResponse event = createEventWithSeatedAndStanding();
        Long eventId = event.id();
        Long sectionId = seatedSectionId(event);
        Long seatId = firstSeatId(sectionId);
        String entryToken = entryTokenRepository.issue(eventId, buyer.getId());
        seatService.hold(buyer.getId(), eventId, entryToken,
                new SeatHoldRequest(sectionId, List.of(seatId), null));
        ReservationResponse response = reservationService.requestPayment(buyer.getId(), entryToken,
                new PaymentRequest(eventId, sectionId, List.of(seatId), null, UUID.randomUUID().toString()));

        reservationService.markPaymentFailed(response.reservationId());
        assertThat(reservationRepository.findById(response.reservationId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.PAYMENT_FAILED);

        reservationService.releaseAfterFailure(response.reservationId());

        Reservation reservation = reservationRepository.findById(response.reservationId()).orElseThrow();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.SEAT_RELEASED);
        List<ReservationSeat> seats = reservationSeatRepository.findAllByReservationId(reservation.getId());
        assertThat(seats.get(0).getStatus()).isEqualTo(ReservationStatus.SEAT_RELEASED);

        // Saga 보상: 좌석이 실제로 AVAILABLE로 반납되고, 다음 시도를 막던 active_reservation도 정리된다.
        assertThat(seatStatusOf(eventId, seatId)).isEqualTo(SeatState.AVAILABLE);
        assertThat(seatService.findActiveHold(eventId, buyer.getId())).isEmpty();
    }

    @Test
    void requestPayment_standing_calculatesQuantityAndAmountWithoutReservationSeat() {
        EventResponse event = createEventWithSeatedAndStanding();
        Long eventId = event.id();
        Long standingSectionId = standingSectionId(event);
        int price = event.sections().stream()
                .filter(s -> s.id().equals(standingSectionId)).findFirst().orElseThrow().price();
        String entryToken = entryTokenRepository.issue(eventId, buyer.getId());
        seatService.hold(buyer.getId(), eventId, entryToken,
                new SeatHoldRequest(standingSectionId, null, 2));

        ReservationResponse response = reservationService.requestPayment(buyer.getId(), entryToken,
                new PaymentRequest(eventId, standingSectionId, null, 2, UUID.randomUUID().toString()));

        Reservation reservation = reservationRepository.findById(response.reservationId()).orElseThrow();
        assertThat(reservation.getQuantity()).isEqualTo(2);
        assertThat(reservation.getAmount()).isEqualTo(price * 2);
        assertThat(reservationSeatRepository.findAllByReservationId(reservation.getId())).isEmpty();
    }

    private EventResponse createEventWithSeatedAndStanding() {
        EventRequest request = new EventRequest(
                "Saga 테스트 콘서트",
                LocalDateTime.now().plusSeconds(30),
                List.of(
                        new SectionRequest("SEATED-TEST", SectionType.SEATED, 10000, 1, 3, null),
                        new SectionRequest("STANDING-TEST", SectionType.STANDING, 5000, null, null, 5)
                ));
        return eventService.register(organizer.getId(), request);
    }

    private Long seatedSectionId(EventResponse event) {
        return event.sections().stream()
                .filter(s -> s.type() == SectionType.SEATED).findFirst().orElseThrow().id();
    }

    private Long standingSectionId(EventResponse event) {
        return event.sections().stream()
                .filter(s -> s.type() == SectionType.STANDING).findFirst().orElseThrow().id();
    }

    private Long firstSeatId(Long sectionId) {
        return seatRepository.findAllBySectionIdOrderByRowNoAscSeatNoAsc(sectionId).get(0).getId();
    }

    private SeatState seatStatusOf(Long eventId, Long seatId) {
        String entryToken = entryTokenRepository.issue(eventId, buyer.getId());
        Long sectionId = seatRepository.findById(seatId).orElseThrow().getSection().getId();
        return seatService.findStatuses(buyer.getId(), eventId, sectionId, entryToken).stream()
                .filter(s -> s.seatId().equals(seatId))
                .findFirst().orElseThrow().status();
    }
}
