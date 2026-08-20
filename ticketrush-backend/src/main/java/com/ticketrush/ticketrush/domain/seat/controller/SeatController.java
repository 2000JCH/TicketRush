package com.ticketrush.ticketrush.domain.seat.controller;

import com.ticketrush.ticketrush.domain.seat.dto.SeatHoldRequest;
import com.ticketrush.ticketrush.domain.seat.dto.SeatHoldResponse;
import com.ticketrush.ticketrush.domain.seat.dto.SeatStatusResponse;
import com.ticketrush.ticketrush.domain.seat.service.SeatService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * api-design.md 4번 좌석(Seat). 이 구간의 모든 API는 인증 + X-Entry-Token 헤더가 필요하다
 * (decisions.md 4번 — 대기열을 통과한 사용자만 접근 가능).
 */
@RestController
@RequestMapping("/api/v1/events/{eventId}/seats")
@RequiredArgsConstructor
public class SeatController {

    private static final String ENTRY_TOKEN_HEADER = "X-Entry-Token";

    private final SeatService seatService;

    @GetMapping
    public List<SeatStatusResponse> findStatuses(
            @AuthenticationPrincipal Long accountId,
            @PathVariable Long eventId,
            @RequestParam Long sectionId,
            @RequestHeader(value = ENTRY_TOKEN_HEADER, required = false) String entryToken) {
        return seatService.findStatuses(accountId, eventId, sectionId, entryToken);
    }

    @PostMapping("/holds")
    public SeatHoldResponse hold(
            @AuthenticationPrincipal Long accountId,
            @PathVariable Long eventId,
            @RequestHeader(value = ENTRY_TOKEN_HEADER, required = false) String entryToken,
            @RequestBody SeatHoldRequest request) {
        return seatService.hold(accountId, eventId, entryToken, request);
    }

    @DeleteMapping("/holds")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(
            @AuthenticationPrincipal Long accountId,
            @PathVariable Long eventId,
            @RequestHeader(value = ENTRY_TOKEN_HEADER, required = false) String entryToken) {
        seatService.release(accountId, eventId, entryToken);
    }
}
