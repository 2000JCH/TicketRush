package com.ticketrush.ticketrush.domain.event.controller;

import com.ticketrush.ticketrush.domain.event.dto.EventRequest;
import com.ticketrush.ticketrush.domain.event.dto.EventResponse;
import com.ticketrush.ticketrush.domain.event.dto.EventSummaryResponse;
import com.ticketrush.ticketrush.domain.event.service.EventService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * api-design.md 2번 이벤트(Event).
 * 조회는 누구나, 등록/수정/삭제는 ORGANIZER만 — 권한은 SecurityConfig가 담당하고
 * "본인이 등록한 이벤트인지"는 EventService가 확인한다.
 */
@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @GetMapping
    public List<EventSummaryResponse> findAll() {
        return eventService.findAll();
    }

    @GetMapping("/{eventId}")
    public EventResponse findById(@PathVariable Long eventId) {
        return eventService.findById(eventId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EventResponse register(@AuthenticationPrincipal Long organizerId,
                                  @Valid @RequestBody EventRequest request) {
        return eventService.register(organizerId, request);
    }

    /** 전체 교체(오픈 전에만 가능). 기존 구역/좌석은 지워지고 요청 내용으로 새로 만들어진다. */
    @PutMapping("/{eventId}")
    public EventResponse update(@AuthenticationPrincipal Long organizerId,
                                @PathVariable Long eventId,
                                @Valid @RequestBody EventRequest request) {
        return eventService.update(organizerId, eventId, request);
    }

    @DeleteMapping("/{eventId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Long organizerId, @PathVariable Long eventId) {
        eventService.delete(organizerId, eventId);
    }
}
