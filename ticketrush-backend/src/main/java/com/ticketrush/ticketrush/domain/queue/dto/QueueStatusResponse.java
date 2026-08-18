package com.ticketrush.ticketrush.domain.queue.dto;

/**
 * 대기 상태 (api-design.md 3번).
 * entryToken이 null이면 아직 대기 중이고, 값이 있으면 좌석 API에 접근할 수 있다.
 * 통과한 사람은 대기열에서 빠지므로 rank는 0으로 내려간다.
 */
public record QueueStatusResponse(long rank, String entryToken) {

    public static QueueStatusResponse waiting(long rank) {
        return new QueueStatusResponse(rank, null);
    }

    public static QueueStatusResponse admitted(String entryToken) {
        return new QueueStatusResponse(0, entryToken);
    }
}
