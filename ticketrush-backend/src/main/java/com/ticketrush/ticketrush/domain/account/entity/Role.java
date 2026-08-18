package com.ticketrush.ticketrush.domain.account.entity;

/** decisions.md 3번의 계정 역할. ORGANIZER는 ADMIN 승인 후에만 활동할 수 있다(12번). */
public enum Role {
    BUYER,
    ORGANIZER,
    ADMIN
}
