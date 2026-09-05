package com.ticketrush.ticketrush.domain.seat.repository;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 구역의 정적 좌석 배치도 캐시(key: {@code seat_catalog:{sectionId}}).
 *
 * {@code seat} 테이블은 등록 후 절대 바뀌지 않는 정적 데이터인데(Seat 엔티티 주석 참고), 좌석 조회
 * API(`SeatService.findStatuses`)가 호출될 때마다 구역 전체(수천~수만 행)를 매번 DB에서 다시
 * 읽고 있었다 — 한계 테스트(2026-09-05 재측정, test-results.md 4번)에서 이게 HikariCP 커넥션 풀을
 * 가장 먼저 고갈시키는 병목으로 확인됐다(동시 100명만 몰려도 대기 67건). 등록 시점에 DB를 딱 한 번
 * 읽어 여기 담아두고, 그 뒤로는 조회 경로에서 DB를 전혀 안 친다.
 *
 * 좌석 배치 순서(row_no, seat_no ASC)를 그대로 보존해야 하는데, Hash는 필드 순회 순서를 보장하지
 * 않아 List로 저장한다 — {@code SeatBulkInsertRepository.insertGrid}가 이미 그 순서로 행을 만들어
 * AUTO_INCREMENT id도 같은 순서로 채번되므로, "id ASC로 한 번 읽어 그대로 RPUSH"만 하면 순서가
 * 저절로 맞는다.
 */
@Repository
@RequiredArgsConstructor
public class SeatCatalogRepository {

    private static final String KEY_PREFIX = "seat_catalog:";

    private final StringRedisTemplate redisTemplate;

    public record Entry(Long seatId, int rowNo, int seatNo) {

        String encode() {
            return seatId + ":" + rowNo + ":" + seatNo;
        }

        static Entry decode(String raw) {
            String[] parts = raw.split(":");
            return new Entry(Long.valueOf(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        }
    }

    /** 이벤트 등록/전체교체 시 딱 한 번 채운다. 기존 값이 있으면(전체교체) 지우고 새로 채운다. */
    public void save(Long sectionId, List<Entry> entries) {
        String key = key(sectionId);
        redisTemplate.delete(key);
        if (entries.isEmpty()) {
            return;
        }
        redisTemplate.opsForList().rightPushAll(key, entries.stream().map(Entry::encode).toList());
    }

    /** @return 캐시 미스(Redis 유실 등)면 빈 리스트 — 호출부가 DB로 폴백하며 다시 채운다. */
    public List<Entry> find(Long sectionId) {
        List<String> raw = redisTemplate.opsForList().range(key(sectionId), 0, -1);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return raw.stream().map(Entry::decode).toList();
    }

    public void delete(Long sectionId) {
        redisTemplate.delete(key(sectionId));
    }

    private String key(Long sectionId) {
        return KEY_PREFIX + sectionId;
    }
}
