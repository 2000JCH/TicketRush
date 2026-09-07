package com.ticketrush.ticketrush.domain.account.service;

import com.ticketrush.ticketrush.domain.account.dto.AccountResponse;
import com.ticketrush.ticketrush.domain.account.entity.Account;
import com.ticketrush.ticketrush.domain.account.entity.AccountStatus;
import com.ticketrush.ticketrush.domain.account.entity.Role;
import com.ticketrush.ticketrush.domain.account.repository.AccountRepository;
import com.ticketrush.ticketrush.domain.account.repository.RefreshTokenRepository;
import com.ticketrush.ticketrush.global.dto.PagedResponse;
import com.ticketrush.ticketrush.global.exception.BusinessException;
import com.ticketrush.ticketrush.global.exception.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADMIN의 계정 관리 (api-design.md 6번).
 * ORGANIZER 승인이 ADMIN 역할의 첫 구체적 기능이고(decisions.md 12번), 이후 회원 목록 조회·
 * 계정 정지(소프트 삭제)가 추가됐다.
 */
@Service
@RequiredArgsConstructor
public class AdminAccountService {

    private static final int MAX_PAGE_SIZE = 100;

    private final AccountRepository accountRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    @Transactional(readOnly = true)
    public List<AccountResponse> findPendingOrganizers() {
        return accountRepository
                .findAllByRoleAndStatusOrderByCreatedAtAsc(Role.ORGANIZER, AccountStatus.PENDING)
                .stream()
                .map(AccountResponse::from)
                .toList();
    }

    /** 관리자 회원 목록 — role/status/email 부분일치 필터 + 페이지네이션(최근 가입 순). */
    @Transactional(readOnly = true)
    public PagedResponse<AccountResponse> findAccounts(
            Role role, AccountStatus status, String email, int page, int size) {
        String emailQuery = (email == null || email.isBlank()) ? null : email.trim();
        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "id"));
        Page<Account> result = accountRepository.search(role, status, emailQuery, pageable);
        return PagedResponse.of(result, AccountResponse::from);
    }

    @Transactional
    public AccountResponse approveOrganizer(Long accountId) {
        Account account = findAccount(accountId);

        // BUYER/ADMIN은 애초에 승인 절차가 없는 역할이라 승인 대상이 아니다.
        if (!account.isOrganizer()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "승인 대상은 ORGANIZER 계정만 가능합니다.");
        }
        if (!account.isPending()) {
            throw new BusinessException(ErrorCode.ACCOUNT_ALREADY_APPROVED);
        }

        account.approve();
        return AccountResponse.from(account);
    }

    /** 승인 거절 — 아직 활성화된 적 없는 PENDING ORGANIZER만 대상이라 행을 삭제한다(재가입 가능). */
    @Transactional
    public void rejectOrganizer(Long accountId) {
        Account account = findAccount(accountId);
        if (!account.isOrganizer() || !account.isPending()) {
            throw new BusinessException(ErrorCode.INVALID_ACCOUNT_STATE, "승인 대기 중인 ORGANIZER만 거절할 수 있습니다.");
        }
        accountRepository.delete(account);
    }

    /** 계정 정지(소프트 삭제) — 로그인/재발급을 막고 Redis의 Refresh Token도 지운다. */
    @Transactional
    public AccountResponse suspend(Long accountId) {
        Account account = findAccount(accountId);
        if (account.isAdmin()) {
            throw new BusinessException(ErrorCode.INVALID_ACCOUNT_STATE, "관리자 계정은 정지할 수 없습니다.");
        }
        if (account.isSuspended()) {
            throw new BusinessException(ErrorCode.INVALID_ACCOUNT_STATE, "이미 정지된 계정입니다.");
        }
        account.suspend();
        refreshTokenRepository.deleteByAccountId(accountId);
        return AccountResponse.from(account);
    }

    /** 정지 해제 — SUSPENDED 계정만 ACTIVE로 되돌린다. */
    @Transactional
    public AccountResponse reactivate(Long accountId) {
        Account account = findAccount(accountId);
        if (!account.isSuspended()) {
            throw new BusinessException(ErrorCode.INVALID_ACCOUNT_STATE, "정지 상태인 계정만 해제할 수 있습니다.");
        }
        account.reactivate();
        return AccountResponse.from(account);
    }

    private Account findAccount(Long accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
    }
}
