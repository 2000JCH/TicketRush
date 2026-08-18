package com.ticketrush.ticketrush.domain.account.service;

import com.ticketrush.ticketrush.domain.account.dto.AccountResponse;
import com.ticketrush.ticketrush.domain.account.entity.Account;
import com.ticketrush.ticketrush.domain.account.entity.AccountStatus;
import com.ticketrush.ticketrush.domain.account.entity.Role;
import com.ticketrush.ticketrush.domain.account.repository.AccountRepository;
import com.ticketrush.ticketrush.global.exception.BusinessException;
import com.ticketrush.ticketrush.global.exception.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADMIN의 계정 관리 (api-design.md 6번).
 * ORGANIZER 승인이 ADMIN 역할의 첫 구체적 기능이다(decisions.md 12번).
 */
@Service
@RequiredArgsConstructor
public class AdminAccountService {

    private final AccountRepository accountRepository;

    @Transactional(readOnly = true)
    public List<AccountResponse> findPendingOrganizers() {
        return accountRepository
                .findAllByRoleAndStatusOrderByCreatedAtAsc(Role.ORGANIZER, AccountStatus.PENDING)
                .stream()
                .map(AccountResponse::from)
                .toList();
    }

    @Transactional
    public AccountResponse approveOrganizer(Long accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));

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
}
