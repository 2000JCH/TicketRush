package com.ticketrush.ticketrush.domain.account.controller;

import com.ticketrush.ticketrush.domain.account.dto.AccountResponse;
import com.ticketrush.ticketrush.domain.account.service.AdminAccountService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * api-design.md 6번 관리자(Admin) — 계정 관리 부분.
 * 접근 권한(ADMIN)은 SecurityConfig의 /api/v1/admin/** 규칙이 담당한다.
 */
@RestController
@RequestMapping("/api/v1/admin/accounts")
@RequiredArgsConstructor
public class AdminAccountController {

    private final AdminAccountService adminAccountService;

    /** 승인 대기 중인 ORGANIZER 목록. 관리자 전용 화면이라 페이징 없이 전체를 내려준다. */
    @GetMapping("/pending")
    public List<AccountResponse> findPendingOrganizers() {
        return adminAccountService.findPendingOrganizers();
    }

    @PatchMapping("/{accountId}/approve")
    public AccountResponse approveOrganizer(@PathVariable Long accountId) {
        return adminAccountService.approveOrganizer(accountId);
    }
}
