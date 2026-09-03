package com.ticketrush.ticketrush.domain.seat.service;

import com.ticketrush.ticketrush.domain.event.entity.Section;
import com.ticketrush.ticketrush.domain.event.entity.SectionType;
import com.ticketrush.ticketrush.domain.event.repository.SectionRepository;
import com.ticketrush.ticketrush.domain.reservation.repository.ReservationRepository;
import com.ticketrush.ticketrush.domain.reservation.repository.ReservationRepository.SectionQuantity;
import com.ticketrush.ticketrush.domain.reservation.repository.ReservationSeatRepository;
import com.ticketrush.ticketrush.domain.seat.repository.SeatStatusRepository;
import com.ticketrush.ticketrush.global.exception.BusinessException;
import com.ticketrush.ticketrush.global.exception.ErrorCode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Redis 데이터 유실 시 seat_status:{eventId} 복구(decisions.md 1번 "Redis 장애 시 홀드 상태 복구
 * 전략"). AOF/RDB를 꺼둔 채 운영하기로 한 결정(redis-design.md, decisions.md 1번 부수 효과)의
 * 다른 한쪽 절반 — Redis가 재시작돼 데이터가 통째로 사라져도, 결제 진행 중/확정된 좌석까지 함께
 * "AVAILABLE"로 보이는 걸 막는 안전장치다.
 *
 * <p><b>2026-09-01 카오스 테스트(A-1, Redis 다운) 중 발견해 그 자리에서 구현함(사용자 확인 완료)</b> —
 * 설계(decisions.md 1번)는 이미 상세히 있었지만 실제 코드에는 반영되어 있지 않았다. 이번 테스트
 * 실행에서는 우연히 실제 오버셀까지 재현되진 않았지만(SQL로 확인), Redis 재시작 후
 * {@code seat_status:{eventId}}에서 결제 진행 중이던 좌석 필드가 사라진 걸 직접 확인했다 —
 * 구조적으로 열려 있던 취약점.
 *
 * <p><b>원 설계와 다르게 구현한 부분</b>:
 * <ul>
 *   <li>원래는 "앱 기동 시 1회 + Redis 재연결 이벤트 리스너"가 트리거였다 — 이러면 그 시점에 존재하는
 *       "활성 이벤트" 전체를 나열해 각각 rebuild해야 하는데, 그 목록을 어떻게 정의할지(오픈 전/후,
 *       종료 후 보관 기간 등)가 추가로 필요해진다. 대신 <b>요청이 실제로 들어온 이벤트에 대해서만,
 *       그 요청 경로에서</b> 확인하는 방식으로 단순화했다({@link #ensureFresh}) — 이벤트 목록을
 *       나열할 필요가 없고, 트래픽이 없는 이벤트는 애초에 rebuild할 이유도 없다.
 *   <li>원래는 새 스테이징 키에 채운 뒤 {@code RENAME}으로 원자적 스왑하는 방식이었다. 여기서는
 *       {@link SeatStatusRepository#applyRebuild}가 라이브 키를 직접 교체하는데, 안전성은 동일하게
 *       보장된다 — rebuild 락을 잡지 못한 나머지 요청은 {@link ErrorCode#SERVICE_TEMPORARILY_UNAVAILABLE}으로
     *       즉시 돌아가 진행 자체를 하지 않으므로, "부분적으로 채워진 Hash"를 읽는 경로가 아예 없다.
 * </ul>
 *
 * <p><b>범위 밖으로 남긴 것</b>: {@code hold_schedule}(만료 스케줄) 자체는 여기서 재구성하지 않는다 —
 * Redis 장애 중 방치된 홀드/결제 요청은 이 rebuild로 "점유 중"까지는 정확히 반영되지만, 그 항목이
 * 스스로 만료되어 자동으로 풀리는 스케줄은 유실된 채로 남는다(다음 결제 시도 실패 시의 보상, 또는
 * 수동 정리로만 해소됨 — 알려진 한계로 문서화).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatStatusRebuildService {

    private static final Duration REBUILD_LOCK_TTL = Duration.ofSeconds(10);

    private final SeatStatusRepository seatStatusRepository;
    private final SectionRepository sectionRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final ReservationRepository reservationRepository;

    @Value("${payment.processing-timeout-millis}")
    private long paymentProcessingTimeoutMillis;

    /**
     * seat_status:{eventId}가 최신 상태인지 확인하고, 아니라면(rebuild 마커 없음) DB 기준으로
     * 재구성한다. 좌석 조회/홀드/해제 등 seat_status를 읽거나 쓰는 모든 진입점이 그 작업을 하기
     * 전에 반드시 호출해야 한다.
     *
     * @throws BusinessException {@link ErrorCode#SERVICE_TEMPORARILY_UNAVAILABLE} — 다른 요청이
     *     이미 이 이벤트를 rebuild 중일 때. 호출자는 진행하지 말고 그대로 실패시켜야 한다(잠시 후
     *     재시도는 클라이언트 몫) — 매진과 반드시 구분해야 하므로 다른 에러 코드를 재사용하지 않는다
     *     (api-design.md 4번).
     */
    public void ensureFresh(Long eventId) {
        if (seatStatusRepository.isRebuildMarkerPresent(eventId)) {
            return;
        }
        if (!seatStatusRepository.tryAcquireRebuildLock(eventId, REBUILD_LOCK_TTL)) {
            throw new BusinessException(ErrorCode.SERVICE_TEMPORARILY_UNAVAILABLE);
        }
        try {
            // 락 대기 중 다른 스레드가 이미 끝냈을 수 있으니 다시 확인한다.
            if (!seatStatusRepository.isRebuildMarkerPresent(eventId)) {
                rebuild(eventId);
            }
        } finally {
            seatStatusRepository.releaseRebuildLock(eventId);
        }
    }

    // 별도 @Transactional을 달지 않는다 — 같은 클래스 안에서 호출돼(self-invocation) 어차피 Spring
    // 프록시를 안 거치므로 붙여도 실제로는 적용되지 않는다. 아래 각 리포지토리 메서드는
    // SimpleJpaRepository 자체가 클래스 레벨 @Transactional(readOnly = true)이라 개별 호출마다
    // 이미 트랜잭션 안에서 실행된다 — 세 쿼리가 하나의 스냅샷을 공유하진 않지만, 서로 다른
    // 데이터(점유 좌석 목록 vs 스탠딩 합계)를 보는 거라 rebuild 목적상 문제되지 않는다.
    void rebuild(Long eventId) {
        LocalDateTime requestedAfter =
                LocalDateTime.now().minus(Duration.ofMillis(paymentProcessingTimeoutMillis));

        Set<Long> occupiedSeatIds = new HashSet<>(
                reservationSeatRepository.findOccupiedSeatIdsByEventId(eventId, requestedAfter));

        List<Section> standingSections = sectionRepository.findAllByEventIdOrderByIdAsc(eventId).stream()
                .filter(section -> section.getType() == SectionType.STANDING)
                .toList();
        Map<Long, Integer> occupiedStanding = reservationRepository
                .sumOccupiedStandingByEventId(eventId, requestedAfter).stream()
                .collect(Collectors.toMap(SectionQuantity::getSectionId, SectionQuantity::getTotal));
        Map<Long, Integer> standingRemaining = new HashMap<>();
        standingSections.forEach(section -> standingRemaining.put(section.getId(),
                Math.max(0, section.getTotalQuantity() - occupiedStanding.getOrDefault(section.getId(), 0))));

        seatStatusRepository.applyRebuild(eventId, occupiedSeatIds, standingRemaining);
        log.warn("seat_status rebuild 완료: eventId={}, occupiedSeats={}, standingSections={}",
                eventId, occupiedSeatIds.size(), standingSections.size());
    }
}
