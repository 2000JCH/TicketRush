package com.ticketrush.ticketrush.domain.event.repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 좌석 격자를 대량 생성한다. 이 프로젝트에서 JPA 대신 SQL을 직접 쓰는 유일한 지점이다.
 *
 * 이유: seat.id가 AUTO_INCREMENT라 JPA(IDENTITY 전략)는 INSERT마다 생성된 ID를 즉시 받아와야 해서
 * hibernate.jdbc.batch_size를 켜도 배치가 적용되지 않는다. 구역 하나가 수천 좌석이 될 수 있어
 * 낱개 INSERT는 등록 한 번에 수천 번의 왕복이 된다.
 * JdbcTemplate.batchUpdate + JDBC URL의 rewriteBatchedStatements=true 조합으로 여러 행을
 * 한 INSERT 문으로 합쳐 보낸다. 좌석 상태는 Redis가 관리하고(decisions.md 1번) 이 테이블은
 * 등록 후 변하지 않는 정적 데이터라, JPA의 이점(더티 체킹 등)을 포기해도 잃는 게 없다.
 */
@Repository
@RequiredArgsConstructor
public class SeatBulkInsertRepository {

    private static final String INSERT_SQL =
            "INSERT INTO seat (section_id, row_no, seat_no, created_at) VALUES (?, ?, ?, ?)";

    /** 한 번에 보낼 최대 행 수. 너무 크면 패킷 크기(max_allowed_packet)에 걸린다. */
    private static final int BATCH_SIZE = 1000;

    private final JdbcTemplate jdbcTemplate;

    public void insertGrid(Long sectionId, int rowCount, int seatsPerRow) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);

        for (int rowNo = 1; rowNo <= rowCount; rowNo++) {
            for (int seatNo = 1; seatNo <= seatsPerRow; seatNo++) {
                batch.add(new Object[] {sectionId, rowNo, seatNo, now});
                if (batch.size() == BATCH_SIZE) {
                    jdbcTemplate.batchUpdate(INSERT_SQL, batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            jdbcTemplate.batchUpdate(INSERT_SQL, batch);
        }
    }
}
