package com.ticketrush.ticketrush.domain.account.controller;

import com.ticketrush.ticketrush.domain.account.dto.AccountResponse;
import com.ticketrush.ticketrush.domain.account.entity.AccountStatus;
import com.ticketrush.ticketrush.domain.account.entity.Role;
import com.ticketrush.ticketrush.domain.account.service.AdminAccountService;
import com.ticketrush.ticketrush.domain.reservation.dto.ReservationDetailResponse;
import com.ticketrush.ticketrush.domain.reservation.service.ReservationService;
import com.ticketrush.ticketrush.global.dto.PagedResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
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
    private final ReservationService reservationService;

    /** 승인 대기 중인 ORGANIZER 목록. 전용 화면이라 페이징 없이 전체를 내려준다. */
    @GetMapping("/pending")
    public List<AccountResponse> findPendingOrganizers() {
        return adminAccountService.findPendingOrganizers();
    }

    /** 전체 회원 목록 — role/status/email 필터 + 페이지네이션(최근 가입 순). */
    @GetMapping
    public PagedResponse<AccountResponse> findAccounts(
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) AccountStatus status,
            @RequestParam(required = false) String email,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return adminAccountService.findAccounts(role, status, email, page, size);
    }

    /** 특정 회원의 예매 내역 (어떤 콘서트·몇 번 자리). */
    @GetMapping("/{accountId}/reservations")
    public List<ReservationDetailResponse> findAccountReservations(@PathVariable Long accountId) {
        return reservationService.findReservationsByAccount(accountId);
    }

    @PatchMapping("/{accountId}/approve")
    public AccountResponse approveOrganizer(@PathVariable Long accountId) {
        return adminAccountService.approveOrganizer(accountId);
    }

    @DeleteMapping("/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rejectOrganizer(@PathVariable Long accountId) {
        adminAccountService.rejectOrganizer(accountId);
    }

    @PatchMapping("/{accountId}/suspend")
    public AccountResponse suspend(@PathVariable Long accountId) {
        return adminAccountService.suspend(accountId);
    }

    @PatchMapping("/{accountId}/reactivate")
    public AccountResponse reactivate(@PathVariable Long accountId) {
        return adminAccountService.reactivate(accountId);
    }
}
