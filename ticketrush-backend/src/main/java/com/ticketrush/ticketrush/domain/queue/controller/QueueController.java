package com.ticketrush.ticketrush.domain.queue.controller;

import com.ticketrush.ticketrush.domain.queue.dto.QueueStatusResponse;
import com.ticketrush.ticketrush.domain.queue.service.QueueService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * api-design.md 3번 대기열(Queue).
 * 대기열 진입 자체는 인증만 필요하고 입장 토큰은 요구하지 않는다 — 그 토큰을 받기 위한 절차이기 때문.
 */
@RestController
@RequestMapping("/api/v1/events/{eventId}/queue/entries")
@RequiredArgsConstructor
public class QueueController {

    private final QueueService queueService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public QueueStatusResponse enter(@AuthenticationPrincipal Long accountId,
                                     @PathVariable Long eventId) {
        return queueService.enter(accountId, eventId);
    }

    /** 클라이언트가 주기적으로 호출해 순번을 확인하고, 입장 토큰이 채워지면 좌석 선택으로 넘어간다. */
    @GetMapping("/me")
    public QueueStatusResponse findMyStatus(@AuthenticationPrincipal Long accountId,
                                            @PathVariable Long eventId) {
        return queueService.findStatus(accountId, eventId);
    }
}
