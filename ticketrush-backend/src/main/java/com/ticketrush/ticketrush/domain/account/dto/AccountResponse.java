package com.ticketrush.ticketrush.domain.account.dto;

import com.ticketrush.ticketrush.domain.account.entity.Account;
import com.ticketrush.ticketrush.domain.account.entity.AccountStatus;
import com.ticketrush.ticketrush.domain.account.entity.Role;
import java.time.LocalDateTime;

/** 관리자 화면에서 쓰는 계정 요약. 비밀번호는 절대 담지 않는다. */
public record AccountResponse(
        Long accountId,
        String email,
        Role role,
        AccountStatus status,
        LocalDateTime createdAt
) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getEmail(),
                account.getRole(),
                account.getStatus(),
                account.getCreatedAt());
    }
}
