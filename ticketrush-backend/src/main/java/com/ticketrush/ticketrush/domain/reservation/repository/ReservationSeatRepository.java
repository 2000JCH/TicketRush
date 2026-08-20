package com.ticketrush.ticketrush.domain.reservation.repository;

import com.ticketrush.ticketrush.domain.reservation.entity.ReservationSeat;
import com.ticketrush.ticketrush.domain.reservation.entity.ReservationStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationSeatRepository extends JpaRepository<ReservationSeat, Long> {

    List<ReservationSeat> findAllByReservationId(Long reservationId);

    /**
     * db-schema.md 6번의 `uq_active_seat`(생성 컬럼 유니크 제약)를 애플리케이션 레벨로 대체한
     * 2차 방어선(CLAUDE.md, 사용자 확인 완료) — 같은 좌석에 진행 중인(PAYMENT_REQUESTED/CONFIRMED)
     * 행이 이미 있는지 조회한다.
     */
    boolean existsBySeatIdAndStatusIn(Long seatId, List<ReservationStatus> statuses);
}
