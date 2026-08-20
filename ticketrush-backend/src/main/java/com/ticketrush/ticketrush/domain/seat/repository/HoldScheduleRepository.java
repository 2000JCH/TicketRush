package com.ticketrush.ticketrush.domain.seat.repository;

import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 홀드 만료 스케줄 (redis-design.md 4-1번, key: hold_schedule).
 *
 * 원래 설계였던 Redis Keyspace Notification(`expired` 이벤트) 구독 방식 대신, "만료 시각순으로
 * 정렬된 할 일 목록"을 애플리케이션이 직접 관리하는 방식으로 구현 단계에서 재설계했다
 * (사용자 확인 완료). 이유는 두 가지: (1) Keyspace Notification은 pub/sub라 그 순간 리스너가
 * 연결되어 있지 않으면(앱 재시작 등) 이벤트가 재전송 없이 영구 유실된다. (2) `expired` 이벤트는
 * 만료된 키 이름만 알려주고 그 시점엔 값이 이미 사라진 뒤라, 스탠딩 홀드를 되돌리는 데 필요한
 * quantity를 읽을 방법이 없었다. member 문자열 자체에 롤백에 필요한 정보를 전부 담아두면 두
 * 문제 모두 피할 수 있다.
 */
@Repository
public class HoldScheduleRepository {

    private static final String KEY = "hold_schedule";

    private final StringRedisTemplate redisTemplate;

    public HoldScheduleRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void schedule(String member, long expiresAtEpochMilli) {
        redisTemplate.opsForZSet().add(KEY, member, expiresAtEpochMilli);
    }

    public void unschedule(String member) {
        redisTemplate.opsForZSet().remove(KEY, member);
    }

    /** 만료 시각이 지난 항목을 오래된 순으로 최대 limit개 가져온다. */
    public Set<String> findDue(long nowEpochMilli, int limit) {
        return redisTemplate.opsForZSet().rangeByScore(KEY, 0, nowEpochMilli, 0, limit);
    }
}
