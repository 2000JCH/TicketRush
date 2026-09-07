package com.ticketrush.ticketrush.domain.account.entity;

/**
 * PENDING은 ORGANIZER 가입 직후 상태로, ADMIN이 승인해야 ACTIVE가 된다.
 * PENDING 상태에서는 로그인 자체가 막힌다(api-design.md ACCOUNT_PENDING).
 *
 * SUSPENDED는 ADMIN이 계정을 정지시킨 상태(소프트 삭제) — 로그인/재발급이 막히고 예약 이력은
 * 보존된다. 하드 삭제(행 DELETE)는 예약·매출 통계가 왜곡돼 채택하지 않았다(decisions.md 참고).
 */
public enum AccountStatus {
    PENDING,
    ACTIVE,
    SUSPENDED
}
