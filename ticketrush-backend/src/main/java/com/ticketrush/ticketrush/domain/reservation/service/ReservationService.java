package com.ticketrush.ticketrush.domain.reservation.service;

import com.ticketrush.ticketrush.domain.account.entity.Account;
import com.ticketrush.ticketrush.domain.account.repository.AccountRepository;
import com.ticketrush.ticketrush.domain.event.entity.Event;
import com.ticketrush.ticketrush.domain.event.entity.Section;
import com.ticketrush.ticketrush.domain.event.entity.Seat;
import com.ticketrush.ticketrush.domain.event.repository.EventRepository;
import com.ticketrush.ticketrush.domain.event.repository.SeatRepository;
import com.ticketrush.ticketrush.domain.event.repository.SectionRepository;
import com.ticketrush.ticketrush.domain.queue.service.QueueService;
import com.ticketrush.ticketrush.domain.reservation.dto.PaymentRequest;
import com.ticketrush.ticketrush.domain.reservation.dto.ReservationResponse;
import com.ticketrush.ticketrush.domain.reservation.entity.Reservation;
import com.ticketrush.ticketrush.domain.reservation.entity.ReservationSeat;
import com.ticketrush.ticketrush.domain.reservation.entity.ReservationStatus;
import com.ticketrush.ticketrush.domain.reservation.repository.IdempotencyRepository;
import com.ticketrush.ticketrush.domain.reservation.repository.ReservationRepository;
import com.ticketrush.ticketrush.domain.reservation.repository.ReservationSeatRepository;
import com.ticketrush.ticketrush.domain.seat.service.SeatService;
import com.ticketrush.ticketrush.global.exception.BusinessException;
import com.ticketrush.ticketrush.global.exception.ErrorCode;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saga 상태머신(decisions.md 5번) — `PAYMENT_REQUESTED → PAYMENT_CONFIRMED`(정상) /
 * `→ PAYMENT_FAILED → SEAT_RELEASED`(보상).
 *
 * 이 단계에서 다루지 않는 것(사용자 확인 완료):
 * - 실제 PG(포트원) 호출: `requestPayment`는 PAYMENT_REQUESTED 행만 만들고 PG는 호출하지 않는다.
 * - `confirmPayment`/`markPaymentFailed`를 실제로 트리거하는 방법(PG 웹훅): 웹훅 서명 검증이
 *   없어 지금은 이 메서드들을 직접 호출하는 자동 테스트로만 검증한다. 3주차 "결제 연동"에서
 *   웹훅 컨트롤러가 이 메서드들을 호출하도록 이어붙인다.
 * - `markPaymentFailed`와 `releaseAfterFailure`를 두 단계로 나눈 이유: decisions.md 5번의
 *   Choreography(Kafka Consumer 기반)에서는 이 둘이 서로 다른 트랜잭션(별도 Consumer 처리)이
 *   될 예정이라, 지금부터 두 개의 독립된 메서드로 분리해두면 3주차에 Kafka Consumer가 각각을
 *   호출하도록 이어붙이기만 하면 된다.
 */
@Service
@RequiredArgsConstructor
public class ReservationService {

    private static final List<ReservationStatus> ACTIVE_STATUSES =
            List.of(ReservationStatus.PAYMENT_REQUESTED, ReservationStatus.PAYMENT_CONFIRMED);

    private final AccountRepository accountRepository;
    private final EventRepository eventRepository;
    private final SectionRepository sectionRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final SeatService seatService;
    private final QueueService queueService;

    @Value("${seat.hold-ttl-millis}")
    private long holdTtlMillis;

    @Value("${payment.processing-timeout-millis}")
    private long paymentProcessingTimeoutMillis;

    @Transactional
    public ReservationResponse requestPayment(Long accountId, String entryToken, PaymentRequest request) {
        Long eventId = request.eventId();
        queueService.validateEntryToken(accountId, eventId, entryToken);

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND));

        SeatService.ActiveHold activeHold = seatService.findActiveHold(eventId, accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACTIVE_HOLD_NOT_FOUND));
        validateMatchesHold(request, activeHold);

        Section section = sectionRepository.findById(activeHold.sectionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT, "존재하지 않는 구역입니다."));

        if (!idempotencyRepository.tryClaim(request.idempotencyKey(), Duration.ofMillis(holdTtlMillis))) {
            throw new BusinessException(ErrorCode.DUPLICATE_PAYMENT_REQUEST);
        }

        int quantity = activeHold.isSeat() ? 1 : activeHold.quantity();
        int amount = section.getPrice() * quantity;

        Reservation reservation;
        try {
            reservation = reservationRepository.save(
                    Reservation.request(account, event, section, quantity, amount, request.idempotencyKey()));
        } catch (DataIntegrityViolationException e) {
            // Redis SETNX가 유실된 경우의 2차 방어선 — DB UNIQUE(idempotency_key) 제약(decisions.md 5번).
            throw new BusinessException(ErrorCode.DUPLICATE_PAYMENT_REQUEST);
        }

        if (activeHold.isSeat()) {
            // db-schema.md 6번 uq_active_seat를 대체하는 애플리케이션 레벨 2차 방어선(사용자 확인 완료).
            // 정상 흐름에서는 Redis 좌석 홀드가 이미 동시성을 막아줘서 여기 걸릴 일이 없다.
            if (reservationSeatRepository.existsBySeatIdAndStatusIn(activeHold.seatId(), ACTIVE_STATUSES)) {
                throw new BusinessException(ErrorCode.SEAT_ALREADY_HELD);
            }
            Seat seat = seatRepository.findById(activeHold.seatId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.SEAT_NOT_FOUND));
            reservationSeatRepository.save(ReservationSeat.of(reservation, seat));
        }

        seatService.reschedulePaymentTimeout(accountId, eventId, activeHold.sectionId(), activeHold.seatId(),
                quantity, Duration.ofMillis(paymentProcessingTimeoutMillis));

        return ReservationResponse.of(reservation);
    }

    /** 정상 경로: PAYMENT_REQUESTED → PAYMENT_CONFIRMED. */
    @Transactional
    public void confirmPayment(Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));
        if (!reservation.isRequested()) {
            return; // 이미 처리된 요청 — 웹훅 중복 수신에 대비한 멱등 처리(decisions.md 5번)
        }
        reservation.confirm();

        List<ReservationSeat> seats = reservationSeatRepository.findAllByReservationId(reservationId);
        seats.forEach(ReservationSeat::confirm);

        Long seatId = seats.isEmpty() ? null : seats.get(0).getSeat().getId();
        seatService.confirmHold(reservation.getAccount().getId(), reservation.getEvent().getId(),
                reservation.getSection().getId(), seatId, reservation.getQuantity());
    }

    /** 보상 1단계: PAYMENT_REQUESTED → PAYMENT_FAILED. */
    @Transactional
    public void markPaymentFailed(Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));
        if (!reservation.isRequested()) {
            return;
        }
        reservation.fail();
    }

    /** 보상 2단계: 좌석/스탠딩 반납 + PAYMENT_FAILED → SEAT_RELEASED. */
    @Transactional
    public void releaseAfterFailure(Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));
        if (reservation.getStatus() != ReservationStatus.PAYMENT_FAILED) {
            return;
        }

        List<ReservationSeat> seats = reservationSeatRepository.findAllByReservationId(reservationId);
        seats.forEach(ReservationSeat::release);

        Long seatId = seats.isEmpty() ? null : seats.get(0).getSeat().getId();
        seatService.compensate(reservation.getAccount().getId(), reservation.getEvent().getId(),
                reservation.getSection().getId(), seatId, reservation.getQuantity());

        reservation.release();
    }

    /** 결제 요청 body가 실제로 들고 있는 홀드와 일치하는지 확인 — 남의 홀드나 옛 홀드로 결제를 시도하지 못하게 막는다. */
    private void validateMatchesHold(PaymentRequest request, SeatService.ActiveHold hold) {
        if (!hold.sectionId().equals(request.sectionId())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "요청한 구역이 현재 홀드와 일치하지 않습니다.");
        }
        if (hold.isSeat()) {
            if (request.seatIds() == null || request.seatIds().size() != 1
                    || !request.seatIds().get(0).equals(hold.seatId())) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "요청한 좌석이 현재 홀드와 일치하지 않습니다.");
            }
        } else if (request.quantity() == null || !request.quantity().equals(hold.quantity())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "요청한 수량이 현재 홀드와 일치하지 않습니다.");
        }
    }
}
