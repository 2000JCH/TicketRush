package com.ticketrush.ticketrush.domain.reservation.repository;

import com.ticketrush.ticketrush.domain.reservation.entity.ReservationSeat;
import com.ticketrush.ticketrush.domain.reservation.entity.ReservationStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationSeatRepository extends JpaRepository<ReservationSeat, Long> {

    List<ReservationSeat> findAllByReservationId(Long reservationId);

    /**
     * db-schema.md 6번의 `uq_active_seat`(생성 컬럼 유니크 제약)를 애플리케이션 레벨로 대체한
     * 2차 방어선(CLAUDE.md, 사용자 확인 완료) — 같은 좌석에 진행 중인(PAYMENT_REQUESTED/CONFIRMED)
     * 행이 이미 있는지 조회한다.
     */
    boolean existsBySeatIdAndStatusIn(Long seatId, List<ReservationStatus> statuses);

    /**
     * Redis 유실 후 seat_status rebuild(decisions.md 1번)에서 "점유 중"으로 간주할 좌석 목록.
     * PAYMENT_CONFIRMED는 무조건 포함하고, PAYMENT_REQUESTED는 결제 처리 타임아웃이 아직 안 지난
     * 것만 포함한다(requestedAfter = now - payment.processing-timeout-millis) — 타임아웃이 지난
     * 건 이미 방치된 시도로 보고 좌석을 다시 풀어준다.
     */
    @Query("SELECT rs.seat.id FROM ReservationSeat rs JOIN rs.reservation r "
            + "WHERE r.event.id = :eventId AND ("
            + "rs.status = com.ticketrush.ticketrush.domain.reservation.entity.ReservationStatus.PAYMENT_CONFIRMED "
            + "OR (rs.status = com.ticketrush.ticketrush.domain.reservation.entity.ReservationStatus.PAYMENT_REQUESTED "
            + "AND r.requestedAt > :requestedAfter))")
    List<Long> findOccupiedSeatIdsByEventId(
            @Param("eventId") Long eventId, @Param("requestedAfter") LocalDateTime requestedAfter);
}
