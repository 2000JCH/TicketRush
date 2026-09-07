package com.ticketrush.ticketrush.domain.event.controller;

import com.ticketrush.ticketrush.domain.event.dto.AdminEventStatsResponse;
import com.ticketrush.ticketrush.domain.event.service.AdminEventStatsService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 콘서트 현황 — 콘서트별 매출 집계 + 좌석 판매현황. 권한은 /api/v1/admin/** 규칙이 담당. */
@RestController
@RequestMapping("/api/v1/admin/events")
@RequiredArgsConstructor
public class AdminEventController {

    private final AdminEventStatsService adminEventStatsService;

    @GetMapping("/stats")
    public List<AdminEventStatsResponse> stats() {
        return adminEventStatsService.findAll();
    }
}
