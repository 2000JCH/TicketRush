package com.ticketrush.ticketrush.domain.reservation.repository;

import com.ticketrush.ticketrush.domain.reservation.entity.Reservation;
import com.ticketrush.ticketrush.domain.reservation.entity.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

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
}
