package com.ticketrush.ticketrush.domain.seat.service;

import com.ticketrush.ticketrush.domain.event.entity.Seat;
import com.ticketrush.ticketrush.domain.event.entity.Section;
import com.ticketrush.ticketrush.domain.event.repository.EventRepository;
import com.ticketrush.ticketrush.domain.event.repository.SeatRepository;
import com.ticketrush.ticketrush.domain.event.repository.SectionRepository;
import com.ticketrush.ticketrush.domain.queue.service.QueueService;
import com.ticketrush.ticketrush.domain.reservation.entity.ReservationStatus;
import com.ticketrush.ticketrush.domain.reservation.repository.ReservationRepository;
import com.ticketrush.ticketrush.domain.seat.dto.SeatHoldRequest;
import com.ticketrush.ticketrush.domain.seat.dto.SeatHoldResponse;
import com.ticketrush.ticketrush.domain.seat.dto.SeatStatusResponse;
import com.ticketrush.ticketrush.domain.seat.entity.SeatState;
import com.ticketrush.ticketrush.domain.seat.lock.GroupHoldLockStrategy;
import com.ticketrush.ticketrush.domain.seat.repository.ActiveReservationRepository;
import com.ticketrush.ticketrush.domain.seat.repository.HoldRepository;
import com.ticketrush.ticketrush.domain.seat.repository.HoldScheduleRepository;
import com.ticketrush.ticketrush.domain.seat.repository.SeatStatusRepository;
import com.ticketrush.ticketrush.global.exception.BusinessException;
import com.ticketrush.ticketrush.global.exception.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 좌석 상태 모델 — 단일/그룹(2매) 좌석 홀드 흐름 + 홀드 TTL/만료 처리(decisions.md 1·2번,
 * api-design.md 4번, redis-design.md 4·4-1번). `reschedulePaymentTimeout`/`confirmHold`/
 * `compensate`는 `ReservationService`(Saga 상태머신)가 결제 요청/확정/실패 시점에 호출한다.
 *
 * 그룹 홀드(좌석 2개 동시 선택)는 `GroupHoldLockStrategy`(Redisson RLock 또는 DB 비관적 락,
 * `group-hold.lock-strategy` 프로퍼티로 전환)로 동시성을 제어한다 — 어느 쪽을 최종 채택할지는
 * 3주차 Gatling 실측 비교 후 결정한다(decisions.md 2번, 사용자 확인 완료).
 */
@Slf4j
@Service
public class SeatService {

    /** 한 건의 예약(홀드 포함)에 담을 수 있는 최대 매수(decisions.md 1번 사재기 방지, 사용자 확인 완료). */
    private static final int MAX_QUANTITY_PER_REQUEST = 2;

    /** 한 번의 스케줄러 실행에서 처리할 최대 만료 건수(redis-design.md 4-1번). */
    private static final int EXPIRY_BATCH_SIZE = 200;

    private final EventRepository eventRepository;
    private final SectionRepository sectionRepository;
    private final SeatRepository seatRepository;
    private final SeatStatusRepository seatStatusRepository;
    private final HoldRepository holdRepository;
    private final ActiveReservationRepository activeReservationRepository;
    private final HoldScheduleRepository holdScheduleRepository;
    private final ReservationRepository reservationRepository;
    private final QueueService queueService;
    private final GroupHoldLockStrategy groupHoldLockStrategy;
    private final Duration holdTtl;

    public SeatService(
            EventRepository eventRepository,
            SectionRepository sectionRepository,
            SeatRepository seatRepository,
            SeatStatusRepository seatStatusRepository,
            HoldRepository holdRepository,
            ActiveReservationRepository activeReservationRepository,
            HoldScheduleRepository holdScheduleRepository,
            ReservationRepository reservationRepository,
            QueueService queueService,
            GroupHoldLockStrategy groupHoldLockStrategy,
            @Value("${seat.hold-ttl-millis}") long holdTtlMillis) {
        this.eventRepository = eventRepository;
        this.sectionRepository = sectionRepository;
        this.seatRepository = seatRepository;
        this.seatStatusRepository = seatStatusRepository;
        this.holdRepository = holdRepository;
        this.activeReservationRepository = activeReservationRepository;
        this.holdScheduleRepository = holdScheduleRepository;
        this.reservationRepository = reservationRepository;
        this.queueService = queueService;
        this.groupHoldLockStrategy = groupHoldLockStrategy;
        this.holdTtl = Duration.ofMillis(holdTtlMillis);
    }

    @Transactional(readOnly = true)
    public List<SeatStatusResponse> findStatuses(
            Long accountId, Long eventId, Long sectionId, String entryToken) {
        queueService.validateEntryToken(accountId, eventId, entryToken);

        Section section = findSection(eventId, sectionId);
        if (section.isStanding()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "스탠딩 구역은 좌석 상태를 조회할 수 없습니다. 잔여 수량은 이벤트 상세 조회를 이용하세요.");
        }

        List<Seat> seats = seatRepository.findAllBySectionIdOrderByRowNoAscSeatNoAsc(sectionId);
        Map<Long, SeatState> statuses = seatStatusRepository.findSeatStatuses(
                eventId, seats.stream().map(Seat::getId).toList());

        return seats.stream()
                .map(seat -> new SeatStatusResponse(
                        seat.getId(), seat.getRowNo(), seat.getSeatNo(), statuses.get(seat.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public SeatHoldResponse hold(Long accountId, Long eventId, String entryToken, SeatHoldRequest request) {
        queueService.validateEntryToken(accountId, eventId, entryToken);
        if (!eventRepository.existsById(eventId)) {
            throw new BusinessException(ErrorCode.EVENT_NOT_FOUND);
        }

        if (request.seatIds() != null && !request.seatIds().isEmpty()) {
            return holdSeat(accountId, eventId, request);
        }
        return holdStanding(accountId, eventId, request);
    }

    public void release(Long accountId, Long eventId, String entryToken) {
        queueService.validateEntryToken(accountId, eventId, entryToken);

        activeReservationRepository.find(eventId, accountId)
                .ifPresent(value -> releaseHold(accountId, eventId, HoldRecord.parse(value)));
    }

    /**
     * 홀드 만료 스케줄 처리(`HoldExpiryScheduler`가 주기적으로 호출, redis-design.md 4-1번).
     * 만료 시각이 지난 항목을 가져와 좌석 상태를 롤백하고 스케줄에서 제거한다.
     */
    public void releaseExpiredHolds() {
        Set<String> due = holdScheduleRepository.findDue(System.currentTimeMillis(), EXPIRY_BATCH_SIZE);
        for (String member : due) {
            try {
                HoldRecord record = HoldRecord.parse(member);
                releaseHold(record.accountId(), record.eventId(), record);
            } catch (Exception e) {
                // 항목 하나가 깨져도 나머지 만료 처리는 계속되어야 한다.
                log.error("홀드 만료 처리 실패: member={}", member, e);
            }
        }
    }

    private SeatHoldResponse holdSeat(Long accountId, Long eventId, SeatHoldRequest request) {
        List<Long> seatIds = validateSeatIds(request.seatIds());
        validateSeatsBelongToSection(eventId, request.sectionId(), seatIds);

        checkAntiScalping(accountId, eventId, seatIds.size());
        HoldRecord record = HoldRecord.forSeats(eventId, accountId, request.sectionId(), seatIds);
        startActiveReservation(accountId, eventId, record);

        try {
            if (seatIds.size() == 1) {
                holdSeatsOrRollback(eventId, seatIds);
            } else {
                // 그룹 홀드(decisions.md 2번) — 두 좌석 다 잡거나 둘 다 실패하도록 락으로 감싼다.
                groupHoldLockStrategy.withLock(eventId, seatIds, () -> holdSeatsOrRollback(eventId, seatIds));
            }
        } catch (BusinessException e) {
            activeReservationRepository.end(eventId, accountId);
            throw e;
        }

        long expiresAtEpochMilli = System.currentTimeMillis() + holdTtl.toMillis();
        seatIds.forEach(seatId -> holdRepository.holdSeat(eventId, seatId, accountId, holdTtl));
        holdScheduleRepository.schedule(record.encode(), expiresAtEpochMilli);
        return SeatHoldResponse.held(toLocalDateTime(expiresAtEpochMilli));
    }

    /** 좌석 하나라도 이미 HELD면 그때까지 잡은 좌석을 즉시 롤백한다(전부 성공 또는 전부 실패). */
    private void holdSeatsOrRollback(Long eventId, List<Long> seatIds) {
        List<Long> held = new ArrayList<>();
        for (Long seatId : seatIds) {
            if (!seatStatusRepository.holdSeat(eventId, seatId)) {
                held.forEach(id -> seatStatusRepository.releaseSeat(eventId, id));
                throw new BusinessException(ErrorCode.SEAT_ALREADY_HELD);
            }
            held.add(seatId);
        }
    }

    private List<Long> validateSeatIds(List<Long> seatIds) {
        if (seatIds == null || seatIds.isEmpty() || seatIds.size() > MAX_QUANTITY_PER_REQUEST) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "seatIds는 1개 이상 " + MAX_QUANTITY_PER_REQUEST + "개 이하여야 합니다.");
        }
        List<Long> sorted = seatIds.stream().distinct().sorted().toList();
        if (sorted.size() != seatIds.size()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "중복된 좌석입니다.");
        }
        return sorted;
    }

    private void validateSeatsBelongToSection(Long eventId, Long sectionId, List<Long> seatIds) {
        List<Seat> seats = seatRepository.findAllById(seatIds);
        if (seats.size() != seatIds.size()) {
            throw new BusinessException(ErrorCode.SEAT_NOT_FOUND);
        }
        boolean allMatch = seats.stream().allMatch(seat ->
                seat.getSection().getId().equals(sectionId)
                        && seat.getSection().getEvent().getId().equals(eventId));
        if (!allMatch) {
            throw new BusinessException(ErrorCode.SEAT_NOT_FOUND);
        }
    }

    private SeatHoldResponse holdStanding(Long accountId, Long eventId, SeatHoldRequest request) {
        Integer quantity = request.quantity();
        if (quantity == null || quantity < 1) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "quantity는 1 이상이어야 합니다.");
        }
        Section section = findSection(eventId, request.sectionId());
        if (!section.isStanding()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "지정석 구역은 seatIds로 요청해야 합니다.");
        }

        checkAntiScalping(accountId, eventId, quantity);
        HoldRecord record = HoldRecord.forStanding(eventId, accountId, request.sectionId(), quantity);
        startActiveReservation(accountId, eventId, record);

        if (!seatStatusRepository.holdStanding(eventId, request.sectionId(), quantity)) {
            activeReservationRepository.end(eventId, accountId);
            throw new BusinessException(ErrorCode.STANDING_SOLD_OUT);
        }

        long expiresAtEpochMilli = System.currentTimeMillis() + holdTtl.toMillis();
        holdRepository.holdStanding(eventId, accountId, request.sectionId(), quantity, holdTtl);
        holdScheduleRepository.schedule(record.encode(), expiresAtEpochMilli);
        return SeatHoldResponse.held(toLocalDateTime(expiresAtEpochMilli));
    }

    /**
     * 계정이 이벤트에 대해 현재 진행 중인 홀드를 조회한다(ReservationService가 결제 요청 시 사용).
     * `active_reservation` 값의 인코딩(`HoldRecord`)은 이 클래스 밖으로 노출하지 않고, 필요한
     * 필드만 담은 공개 DTO(`ActiveHold`)로 변환해 돌려준다.
     */
    @Transactional(readOnly = true)
    public Optional<ActiveHold> findActiveHold(Long eventId, Long accountId) {
        return activeReservationRepository.find(eventId, accountId)
                .map(HoldRecord::parse)
                .map(record -> new ActiveHold(record.sectionId(), record.seatIds(), record.quantity()));
    }

    /**
     * 결제 요청 시점에 스케줄을 결제 처리 타임아웃으로 재조정한다(redis-design.md 4-1번 —
     * 원래 설계의 "TTL 재설정"에 대응하는 `ZADD` upsert). `hold`/`active_reservation` 키의
     * 보조 TTL도 같은 타임아웃으로 함께 갱신한다. seatIds가 비어있으면 스탠딩 홀드로 취급한다.
     */
    public LocalDateTime reschedulePaymentTimeout(
            Long accountId, Long eventId, Long sectionId, List<Long> seatIds, int quantity, Duration timeout) {
        HoldRecord record = isSeatHold(seatIds)
                ? HoldRecord.forSeats(eventId, accountId, sectionId, seatIds)
                : HoldRecord.forStanding(eventId, accountId, sectionId, quantity);

        long expiresAtEpochMilli = System.currentTimeMillis() + timeout.toMillis();
        holdScheduleRepository.schedule(record.encode(), expiresAtEpochMilli);
        if (record.seat()) {
            seatIds.forEach(seatId -> holdRepository.holdSeat(eventId, seatId, accountId, timeout));
        } else {
            holdRepository.holdStanding(eventId, accountId, sectionId, quantity, timeout);
        }
        activeReservationRepository.refresh(eventId, accountId, record.encode(), timeout);
        return toLocalDateTime(expiresAtEpochMilli);
    }

    /**
     * 결제 확정 시 스케줄에서 완전히 제거해 다시는 만료되지 않게 한다(원래 설계의 `PERSIST`에
     * 대응). `seat_status` Hash는 그대로 `HELD`로 남아 "확정 판매"를 나타내므로 건드리지 않는다
     * (redis-design.md 3번) — `hold`/`active_reservation` 키는 더 이상 아무 코드도 읽지 않는
     * 보조 기록일 뿐이라 정리 차원에서 지운다.
     */
    public void confirmHold(Long accountId, Long eventId, Long sectionId, List<Long> seatIds, int quantity) {
        HoldRecord record = isSeatHold(seatIds)
                ? HoldRecord.forSeats(eventId, accountId, sectionId, seatIds)
                : HoldRecord.forStanding(eventId, accountId, sectionId, quantity);

        holdScheduleRepository.unschedule(record.encode());
        if (record.seat()) {
            seatIds.forEach(seatId -> holdRepository.releaseSeat(eventId, seatId));
        } else {
            holdRepository.releaseStanding(eventId, accountId, sectionId);
        }
        activeReservationRepository.end(eventId, accountId);
    }

    /** 결제 실패 시 Saga 보상 — 좌석/스탠딩을 원상복구한다(ReservationService가 호출). */
    public void compensate(Long accountId, Long eventId, Long sectionId, List<Long> seatIds, int quantity) {
        HoldRecord record = isSeatHold(seatIds)
                ? HoldRecord.forSeats(eventId, accountId, sectionId, seatIds)
                : HoldRecord.forStanding(eventId, accountId, sectionId, quantity);
        releaseHold(accountId, eventId, record);
    }

    /**
     * 결제 확정(PAYMENT_CONFIRMED)된 예약의 취소(decisions.md 9번, 전액 취소 MVP)에서 쓴다.
     * {@code compensate}와 달리 이건 이미 "판매 완료"로 seat_status에 HELD로 남아있던 좌석을
     * 되돌리는 것이라, hold/active_reservation/hold_schedule은 건드리지 않는다 — confirmHold
     * 시점에 이미 전부 정리돼 있기 때문이다(그 세 키는 진행 중인 홀드에만 존재한다).
     */
    public void releaseConfirmed(Long eventId, Long sectionId, List<Long> seatIds, int quantity) {
        if (isSeatHold(seatIds)) {
            seatIds.forEach(seatId -> seatStatusRepository.releaseSeat(eventId, seatId));
        } else {
            seatStatusRepository.releaseStanding(eventId, sectionId, quantity);
        }
    }

    private boolean isSeatHold(List<Long> seatIds) {
        return seatIds != null && !seatIds.isEmpty();
    }

    /** 계정이 이벤트에 대해 현재 진행 중인 홀드(결제 요청 대상). seatIds가 비어있으면 스탠딩. */
    public record ActiveHold(Long sectionId, List<Long> seatIds, int quantity) {
        public boolean isSeat() {
            return seatIds != null && !seatIds.isEmpty();
        }
    }

    /** 명시적 해제와 만료 스케줄 처리가 공유하는 롤백 로직. */
    private void releaseHold(Long accountId, Long eventId, HoldRecord record) {
        if (record.seat()) {
            record.seatIds().forEach(seatId -> {
                seatStatusRepository.releaseSeat(eventId, seatId);
                holdRepository.releaseSeat(eventId, seatId);
            });
        } else {
            seatStatusRepository.releaseStanding(eventId, record.sectionId(), record.quantity());
            holdRepository.releaseStanding(eventId, accountId, record.sectionId());
        }
        holdScheduleRepository.unschedule(record.encode());
        activeReservationRepository.end(eventId, accountId);
    }

    /**
     * 사재기 방지 검증 중 1·3번(api-design.md 4번) — 2번(ACTIVE_RESERVATION_EXISTS)은
     * startActiveReservation이 담당한다.
     */
    private void checkAntiScalping(Long accountId, Long eventId, int requestedQuantity) {
        if (requestedQuantity > MAX_QUANTITY_PER_REQUEST) {
            throw new BusinessException(ErrorCode.QUANTITY_LIMIT_EXCEEDED);
        }
        int confirmed = reservationRepository.sumQuantityByAccountIdAndEventIdAndStatus(
                accountId, eventId, ReservationStatus.PAYMENT_CONFIRMED);
        if (confirmed + requestedQuantity > MAX_QUANTITY_PER_REQUEST) {
            throw new BusinessException(ErrorCode.QUANTITY_LIMIT_EXCEEDED);
        }
    }

    private void startActiveReservation(Long accountId, Long eventId, HoldRecord record) {
        if (!activeReservationRepository.tryStart(eventId, accountId, record.encode(), holdTtl)) {
            throw new BusinessException(ErrorCode.ACTIVE_RESERVATION_EXISTS);
        }
    }

    private Section findSection(Long eventId, Long sectionId) {
        Section section = sectionRepository.findById(sectionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT, "존재하지 않는 구역입니다."));
        if (!section.getEvent().getId().equals(eventId)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "해당 이벤트의 구역이 아닙니다.");
        }
        return section;
    }

    private LocalDateTime toLocalDateTime(long epochMilli) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), ZoneId.systemDefault());
    }

    /**
     * 홀드 1건을 나타내는 인코딩. `active_reservation` 키의 값과 `hold_schedule`의 member로 동일한
     * 문자열을 그대로 재사용한다(redis-design.md 4-1·8번) — 두 곳이 서로 다른 인코딩을 쓰면 어긋날
     * 위험이 있어, 단일 소스로 통일했다. 그룹 홀드(decisions.md 2번)라 좌석이 최대 2개일 수 있어
     * seatId 하나가 아니라 쉼표로 구분한 목록을 담는다.
     *
     * 형식: "SEAT:{eventId}:{accountId}:{sectionId}:{seatId1}[,{seatId2}]" /
     * "STANDING:{eventId}:{accountId}:{sectionId}:{quantity}"
     */
    private record HoldRecord(
            boolean seat, Long eventId, Long accountId, Long sectionId, List<Long> seatIds, int quantity) {

        static HoldRecord forSeats(Long eventId, Long accountId, Long sectionId, List<Long> seatIds) {
            return new HoldRecord(true, eventId, accountId, sectionId, seatIds, 0);
        }

        static HoldRecord forStanding(Long eventId, Long accountId, Long sectionId, int quantity) {
            return new HoldRecord(false, eventId, accountId, sectionId, List.of(), quantity);
        }

        String encode() {
            return seat
                    ? "SEAT:" + eventId + ":" + accountId + ":" + sectionId + ":"
                            + String.join(",", seatIds.stream().map(String::valueOf).toList())
                    : "STANDING:" + eventId + ":" + accountId + ":" + sectionId + ":" + quantity;
        }

        static HoldRecord parse(String value) {
            String[] parts = value.split(":");
            boolean isSeat = parts[0].equals("SEAT");
            Long eventId = Long.valueOf(parts[1]);
            Long accountId = Long.valueOf(parts[2]);
            Long sectionId = Long.valueOf(parts[3]);
            if (isSeat) {
                List<Long> seatIds = Arrays.stream(parts[4].split(",")).map(Long::valueOf).toList();
                return new HoldRecord(true, eventId, accountId, sectionId, seatIds, 0);
            }
            return new HoldRecord(false, eventId, accountId, sectionId, List.of(), Integer.parseInt(parts[4]));
        }
    }
}
