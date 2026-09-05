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
import com.ticketrush.ticketrush.domain.reservation.dto.ReservationDetailResponse;
import com.ticketrush.ticketrush.domain.reservation.dto.ReservationResponse;
import com.ticketrush.ticketrush.domain.reservation.entity.OutboxEvent;
import com.ticketrush.ticketrush.domain.reservation.entity.Reservation;
import com.ticketrush.ticketrush.domain.reservation.entity.ReservationSeat;
import com.ticketrush.ticketrush.domain.reservation.entity.ReservationStatus;
import com.ticketrush.ticketrush.domain.reservation.repository.IdempotencyRepository;
import com.ticketrush.ticketrush.domain.reservation.repository.OutboxEventRepository;
import com.ticketrush.ticketrush.domain.reservation.repository.ReservationRepository;
import com.ticketrush.ticketrush.domain.reservation.repository.ReservationSeatRepository;
import com.ticketrush.ticketrush.domain.seat.service.SeatService;
import com.ticketrush.ticketrush.global.exception.BusinessException;
import com.ticketrush.ticketrush.global.exception.ErrorCode;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

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
    private final OutboxEventRepository outboxEventRepository;
    private final SeatService seatService;
    private final QueueService queueService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @Value("${seat.hold-ttl-millis}")
    private long holdTtlMillis;

    @Value("${payment.processing-timeout-millis}")
    private long paymentProcessingTimeoutMillis;

    /**
     * DB 트랜잭션은 실제로 DB에 쓰는 구간(아래 `transactionTemplate.execute`)만 감싼다
     * (2026-09-05, 한계 테스트 원인 진단 — `test-results.md` 4-5). 원래는 메서드 전체가
     * `@Transactional`이라 입장 토큰 검증·활성 홀드 조회·멱등키 클레임(전부 Redis)까지 DB
     * 커넥션을 붙잡은 채 실행되고 있었다. 유일한 예외는 맨 끝 `reschedulePaymentTimeout`
     * (Redis)인데, 이건 트랜잭션 밖으로 빼지 않고 그대로 안에 둔다 — 밖으로 빼면 예약은 DB에
     * 커밋됐는데 좌석 만료 스케줄 갱신만 실패하는 경우 결제 처리 중인 좌석이 원래 홀드 TTL
     * 기준으로 먼저 풀려버릴 위험이 있어서, "예약 저장 + 스케줄 갱신"을 여전히 하나로 묶는다.
     */
    public ReservationResponse requestPayment(Long accountId, String entryToken, PaymentRequest request) {
        Long eventId = request.eventId();
        queueService.validateEntryToken(accountId, eventId, entryToken);

        SeatService.ActiveHold activeHold = seatService.findActiveHold(eventId, accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACTIVE_HOLD_NOT_FOUND));
        validateMatchesHold(request, activeHold);

        if (!idempotencyRepository.tryClaim(request.idempotencyKey(), Duration.ofMillis(holdTtlMillis))) {
            throw new BusinessException(ErrorCode.DUPLICATE_PAYMENT_REQUEST);
        }

        return transactionTemplate.execute(status -> requestPaymentInTransaction(accountId, eventId, request, activeHold));
    }

    private ReservationResponse requestPaymentInTransaction(
            Long accountId, Long eventId, PaymentRequest request, SeatService.ActiveHold activeHold) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND));
        Section section = sectionRepository.findById(activeHold.sectionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT, "존재하지 않는 구역입니다."));

        int quantity = activeHold.isSeat() ? activeHold.seatIds().size() : activeHold.quantity();
        int amount = section.getPrice() * quantity;

        Reservation reservation;
        try {
            reservation = reservationRepository.save(
                    Reservation.request(account, event, section, quantity, amount, request.idempotencyKey()));
        } catch (DataIntegrityViolationException e) {
            // Redis SETNX가 유실된 경우의 2차 방어선 — DB UNIQUE(idempotency_key) 제약(decisions.md 5번).
            throw new BusinessException(ErrorCode.DUPLICATE_PAYMENT_REQUEST);
        }
        // 프론트가 PortOne SDK 호출 시 넘길 merchant 결제 식별자. 웹훅 수신 시 이 값으로 역참조한다
        // (PaymentWebhookService). reservation.getId()는 save() 직후(IDENTITY 전략)에만 존재한다.
        reservation.assignPgPaymentId("TICKETRUSH-" + reservation.getId());

        if (activeHold.isSeat()) {
            // db-schema.md 6번 uq_active_seat를 대체하는 애플리케이션 레벨 2차 방어선(사용자 확인 완료).
            // 정상 흐름에서는 Redis 좌석 홀드가 이미 동시성을 막아줘서 여기 걸릴 일이 없다.
            for (Long seatId : activeHold.seatIds()) {
                if (reservationSeatRepository.existsBySeatIdAndStatusIn(seatId, ACTIVE_STATUSES)) {
                    throw new BusinessException(ErrorCode.SEAT_ALREADY_HELD);
                }
            }
            List<Seat> seats = seatRepository.findAllById(activeHold.seatIds());
            if (seats.size() != activeHold.seatIds().size()) {
                throw new BusinessException(ErrorCode.SEAT_NOT_FOUND);
            }
            seats.forEach(seat -> reservationSeatRepository.save(ReservationSeat.of(reservation, seat)));
        }

        seatService.reschedulePaymentTimeout(accountId, eventId, activeHold.sectionId(), activeHold.seatIds(),
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

        List<Long> seatIds = seats.stream().map(rs -> rs.getSeat().getId()).toList();
        seatService.confirmHold(reservation.getAccount().getId(), reservation.getEvent().getId(),
                reservation.getSection().getId(), seatIds, reservation.getQuantity());
    }

    /**
     * 보상 1단계: PAYMENT_REQUESTED → PAYMENT_FAILED. 같은 트랜잭션 안에서 outbox_events에도
     * 이벤트를 남긴다(decisions.md 6번 Outbox 패턴) — Debezium이 이 INSERT를 감지해 Kafka로
     * 발행하면, 별도 Consumer가 이를 받아 보상 2단계({@link #releaseAfterFailure})를 트리거한다.
     * decisions.md 5번의 Choreography(중앙 조율자 없이 Kafka Consumer로 다음 단계를 잇는 방식)를
     * 그대로 구현한 것 — 실패 감지와 좌석 반납이 서로 다른 트랜잭션으로 분리된다.
     */
    @Transactional
    public void markPaymentFailed(Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));
        if (!reservation.isRequested()) {
            return;
        }
        reservation.fail();

        String payload = objectMapper.writeValueAsString(new PaymentFailedPayload(reservationId));
        outboxEventRepository.save(OutboxEvent.paymentFailed(reservationId, payload));
    }

    private record PaymentFailedPayload(Long reservationId) {
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

        List<Long> seatIds = seats.stream().map(rs -> rs.getSeat().getId()).toList();
        seatService.compensate(reservation.getAccount().getId(), reservation.getEvent().getId(),
                reservation.getSection().getId(), seatIds, reservation.getQuantity());

        reservation.release();
    }

    @Transactional(readOnly = true)
    public List<ReservationDetailResponse> findMyReservations(Long accountId) {
        return reservationRepository.findAllByAccountIdOrderByRequestedAtDesc(accountId).stream()
                .map(ReservationDetailResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public ReservationDetailResponse findDetail(Long accountId, Long reservationId) {
        Reservation reservation = findOwnedReservation(accountId, reservationId);
        return ReservationDetailResponse.of(reservation);
    }

    /**
     * 예약 취소(decisions.md 9번, MVP: 전액 취소만). PAYMENT_CONFIRMED 상태에서만 가능하다 —
     * 진행 중인 결제 요청은 웹훅/TTL이 알아서 정리하고, 이미 끝난 예약은 취소할 대상이 없다.
     * 도착 상태는 Saga 보상(releaseAfterFailure)과 동일한 SEAT_RELEASED를 재사용하지만
     * ({@code compensate}가 아니라) {@link com.ticketrush.ticketrush.domain.seat.service.SeatService#releaseConfirmed}로
     * 반납한다 — 이미 confirmHold에서 hold/active_reservation 흔적이 정리된 "판매 완료" 좌석이기
     * 때문이다.
     */
    @Transactional
    public ReservationDetailResponse cancel(Long accountId, Long reservationId) {
        Reservation reservation = findOwnedReservation(accountId, reservationId);
        if (reservation.getStatus() != ReservationStatus.PAYMENT_CONFIRMED) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_CANCELLABLE);
        }

        List<ReservationSeat> seats = reservationSeatRepository.findAllByReservationId(reservationId);
        seats.forEach(ReservationSeat::release);
        List<Long> seatIds = seats.stream().map(rs -> rs.getSeat().getId()).toList();

        seatService.releaseConfirmed(
                reservation.getEvent().getId(), reservation.getSection().getId(), seatIds, reservation.getQuantity());
        reservation.release();

        return ReservationDetailResponse.of(reservation);
    }

    private Reservation findOwnedReservation(Long accountId, Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));
        if (!reservation.getAccount().getId().equals(accountId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return reservation;
    }

    /** 결제 요청 body가 실제로 들고 있는 홀드와 일치하는지 확인 — 남의 홀드나 옛 홀드로 결제를 시도하지 못하게 막는다. */
    private void validateMatchesHold(PaymentRequest request, SeatService.ActiveHold hold) {
        if (!hold.sectionId().equals(request.sectionId())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "요청한 구역이 현재 홀드와 일치하지 않습니다.");
        }
        if (hold.isSeat()) {
            Set<Long> requestedSeatIds = request.seatIds() == null ? Set.of() : new HashSet<>(request.seatIds());
            if (!requestedSeatIds.equals(new HashSet<>(hold.seatIds()))) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "요청한 좌석이 현재 홀드와 일치하지 않습니다.");
            }
        } else if (request.quantity() == null || !request.quantity().equals(hold.quantity())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "요청한 수량이 현재 홀드와 일치하지 않습니다.");
        }
    }
}
