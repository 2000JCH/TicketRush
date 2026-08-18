package com.ticketrush.ticketrush.domain.queue.service;

import com.ticketrush.ticketrush.domain.event.repository.EventRepository;
import com.ticketrush.ticketrush.domain.queue.dto.QueueStatusResponse;
import com.ticketrush.ticketrush.domain.queue.repository.EntryTokenRepository;
import com.ticketrush.ticketrush.domain.queue.repository.QueueRepository;
import com.ticketrush.ticketrush.global.exception.BusinessException;
import com.ticketrush.ticketrush.global.exception.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 가상 대기실 (decisions.md 4번, api-design.md 3번).
 *
 * Nginx Rate Limiting과 역할이 다르다 — Rate Limiting은 초과 요청을 순서와 무관하게 거절할 뿐이고,
 * 대기열은 "먼저 온 사람이 먼저 산다"는 공정성을 보장한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueueService {

    private final QueueRepository queueRepository;
    private final EntryTokenRepository entryTokenRepository;
    private final EventRepository eventRepository;

    /** 한 번에 입장시킬 인원. 홀드 TTL·좌석 수와 함께 3주차 부하 테스트에서 조정할 값이다. */
    @Value("${queue.admit-count}")
    private int admitCount;

    public QueueStatusResponse enter(Long accountId, Long eventId) {
        if (!eventRepository.existsById(eventId)) {
            throw new BusinessException(ErrorCode.EVENT_NOT_FOUND);
        }

        // 이미 통과해 토큰을 들고 있으면 대기열에 다시 넣지 않는다(뒤로 밀리는 것을 방지).
        return entryTokenRepository.find(eventId, accountId)
                .map(QueueStatusResponse::admitted)
                .orElseGet(() -> {
                    queueRepository.enter(eventId, accountId);
                    return findStatus(accountId, eventId);
                });
    }

    /** 클라이언트가 주기적으로 폴링하는 순번 조회. */
    public QueueStatusResponse findStatus(Long accountId, Long eventId) {
        return entryTokenRepository.find(eventId, accountId)
                .map(QueueStatusResponse::admitted)
                .orElseGet(() -> queueRepository.findRank(eventId, accountId)
                        .map(QueueStatusResponse::waiting)
                        // 대기열에도 없고 토큰도 없다 = 진입한 적이 없거나 토큰이 만료된 상태.
                        // 만료된 경우 순번을 유지한 채 재발급하지 않고 새로 진입시킨다(decisions.md 4번 공정성).
                        .orElseThrow(() -> new BusinessException(ErrorCode.QUEUE_ENTRY_NOT_FOUND)));
    }

    /**
     * 대기열 앞쪽 인원에게 입장 토큰을 발급하고 대기열에서 제거한다(Scheduler가 주기적으로 호출).
     *
     * 토큰 발급을 먼저 하고 제거를 나중에 하는 순서가 중요하다 — 반대로 하면 제거 직후 실패했을 때
     * 대기열에서도 빠지고 토큰도 없는 상태가 되어 사용자가 통째로 유실된다.
     * 이 순서라면 최악의 경우 "토큰은 받았는데 대기열에도 남아있는" 상태인데,
     * 조회 시 토큰을 먼저 보므로 사용자에겐 정상이고 다음 주기에 정리된다.
     */
    public int admitFront(Long eventId) {
        List<Long> accountIds = queueRepository.findFront(eventId, admitCount);
        if (accountIds.isEmpty()) {
            return 0;
        }

        accountIds.forEach(accountId -> entryTokenRepository.issue(eventId, accountId));
        queueRepository.remove(eventId, accountIds);

        log.debug("이벤트 {} 대기열에서 {}명 입장 처리", eventId, accountIds.size());
        return accountIds.size();
    }
}
