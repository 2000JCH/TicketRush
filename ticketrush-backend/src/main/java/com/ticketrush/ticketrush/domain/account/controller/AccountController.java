package com.ticketrush.ticketrush.domain.account.controller;

import com.ticketrush.ticketrush.domain.account.dto.AccountResponse;
import com.ticketrush.ticketrush.domain.account.entity.Account;
import com.ticketrush.ticketrush.domain.account.repository.AccountRepository;
import com.ticketrush.ticketrush.global.exception.BusinessException;
import com.ticketrush.ticketrush.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 로그인한 본인의 계정 정보 조회 — "내 정보" 화면용. */
@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountRepository accountRepository;

    @GetMapping("/me")
    public AccountResponse me(@AuthenticationPrincipal Long accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        return AccountResponse.from(account);
    }
}
