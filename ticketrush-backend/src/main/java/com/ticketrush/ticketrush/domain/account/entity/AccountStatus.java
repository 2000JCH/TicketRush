package com.ticketrush.ticketrush.domain.account.entity;

/**
 * PENDING은 ORGANIZER 가입 직후 상태로, ADMIN이 승인해야 ACTIVE가 된다.
 * PENDING 상태에서는 로그인 자체가 막힌다(api-design.md ACCOUNT_PENDING).
 */
public enum AccountStatus {
    PENDING,
    ACTIVE
}
