package com.ticketrush.ticketrush.domain.reservation.repository;

import com.ticketrush.ticketrush.domain.reservation.entity.Reservation;
import com.ticketrush.ticketrush.domain.reservation.entity.ReservationStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    Optional<Reservation> findByPgPaymentId(String pgPaymentId);

    List<Reservation> findAllByAccountIdOrderByRequestedAtDesc(Long accountId);

    /**
     * 계정당 이벤트별 누적 확정 매수 (decisions.md 1번 사재기 방지 3번째 규칙, db-schema.md
     * idx_account_event_status). 결제 연동(Saga) 전이라 지금은 PAYMENT_CONFIRMED 행이 없어 항상 0을
     * 반환한다 — 3주차에 실제 값이 쌓이기 시작한다.
     */
    @Query("SELECT COALESCE(SUM(r.quantity), 0) FROM Reservation r "
            + "WHERE r.account.id = :accountId AND r.event.id = :eventId AND r.status = :status")
    int sumQuantityByAccountIdAndEventIdAndStatus(
            @Param("accountId") Long accountId,
            @Param("eventId") Long eventId,
            @Param("status") ReservationStatus status);

    /**
     * Redis 유실 후 STANDING 구역 seat_status rebuild(decisions.md 1번)에서 쓰는, 구역별 점유
     * 수량 합계. ReservationSeatRepository.findOccupiedSeatIdsByEventId와 동일한 "점유 중" 기준
     * (PAYMENT_CONFIRMED + 타임아웃 안 지난 PAYMENT_REQUESTED)을 STANDING 구역에 적용한 버전이다.
     */
    @Query("SELECT r.section.id AS sectionId, COALESCE(SUM(r.quantity), 0) AS total FROM Reservation r "
            + "WHERE r.event.id = :eventId AND r.section.type = "
            + "com.ticketrush.ticketrush.domain.event.entity.SectionType.STANDING AND ("
            + "r.status = com.ticketrush.ticketrush.domain.reservation.entity.ReservationStatus.PAYMENT_CONFIRMED "
            + "OR (r.status = com.ticketrush.ticketrush.domain.reservation.entity.ReservationStatus.PAYMENT_REQUESTED "
            + "AND r.requestedAt > :requestedAfter)) "
            + "GROUP BY r.section.id")
    List<SectionQuantity> sumOccupiedStandingByEventId(
            @Param("eventId") Long eventId, @Param("requestedAfter") LocalDateTime requestedAfter);

    interface SectionQuantity {
        Long getSectionId();
        Integer getTotal();
    }
}
