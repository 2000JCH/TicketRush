package com.ticketrush.ticketrush.domain.event.repository;

import com.ticketrush.ticketrush.domain.event.entity.Seat;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    long countBySectionId(Long sectionId);

    /** 좌석 상태 조회 API(api-design.md 4번)에서 좌석 배치 순서대로 내려주기 위해 정렬해서 조회한다. */
    List<Seat> findAllBySectionIdOrderByRowNoAscSeatNoAsc(Long sectionId);

    /**
     * 그룹 좌석 홀드의 DB 비관적 락 구현(decisions.md 2번)에서만 쓴다. seat 행 자체를 뮤텍스로
     * 빌리는 것뿐이라 반환값은 쓰지 않고, 호출하는 트랜잭션이 끝날 때까지 해당 행을 잠근다.
     *
     * <p>lock.timeout 힌트로 {@code SELECT ... FOR UPDATE WAIT 3}(MySQL 8)이 나가게 한다 — 값
     * 3,000ms는 {@code group-hold.lock-wait-millis} 기본값(= Redisson {@code tryLock} 대기시간)과
     * 맞춘 것이다. 벤치마크에서 두 락의 "실패 방식"(즉시 vs 몇 초 대기 후)을 같게 만들기 위함
     * (decisions.md 2번, 멘토 피드백). 타임아웃 시 Spring이 던지는 락 획득 실패 예외는
     * {@link com.ticketrush.ticketrush.domain.seat.lock.DbPessimisticLockGroupHoldLockStrategy}가
     * {@code GROUP_HOLD_LOCK_TIMEOUT}으로 매핑한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("SELECT s FROM Seat s WHERE s.id IN :seatIds ORDER BY s.id ASC")
    List<Seat> findAllByIdInForUpdate(@Param("seatIds") List<Long> seatIds);

    /**
     * 오픈 전 전체 교체/삭제 시 이벤트에 속한 좌석을 한 번에 지운다.
     * 좌석이 수천 개일 수 있어 엔티티를 하나씩 지우지 않고 DELETE 한 문장으로 처리한다.
     */
    @Modifying
    @Query("DELETE FROM Seat s WHERE s.section.id IN "
            + "(SELECT sec.id FROM Section sec WHERE sec.event.id = :eventId)")
    void deleteAllByEventId(@Param("eventId") Long eventId);
}
