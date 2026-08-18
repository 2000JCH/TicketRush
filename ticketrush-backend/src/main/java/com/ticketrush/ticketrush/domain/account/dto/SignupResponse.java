package com.ticketrush.ticketrush.domain.account.dto;

import com.ticketrush.ticketrush.domain.account.entity.AccountStatus;
import com.ticketrush.ticketrush.domain.account.entity.Role;

/**
 * status를 함께 돌려주는 이유: ORGANIZER는 가입 직후 PENDING이라 바로 로그인할 수 없다.
 * 클라이언트가 "승인 대기 중" 안내를 띄울 수 있도록 상태를 신호로 전달한다.
 */
public record SignupResponse(Long accountId, Role role, AccountStatus status) {
}
