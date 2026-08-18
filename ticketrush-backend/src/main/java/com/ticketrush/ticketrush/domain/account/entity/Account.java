package com.ticketrush.ticketrush.domain.account.entity;

import com.ticketrush.ticketrush.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 인증 계정 (db-schema.md 1번).
 * Refresh Token은 이 테이블이 아니라 Redis(refresh_token:{accountId})에만 저장한다.
 */
@Getter
@Entity
@Table(name = "account")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String email;

    @Column(nullable = false, length = 255)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    private Account(String email, String encodedPassword, Role role, AccountStatus status) {
        this.email = email;
        this.password = encodedPassword;
        this.role = role;
        this.status = status;
    }

    /**
     * 회원가입으로 계정을 만든다.
     * ORGANIZER는 콘서트 등록 권한이라 ADMIN 승인 전까지 PENDING으로 시작한다(decisions.md 12번).
     * ADMIN은 셀프 가입 대상이 아니므로 이 메서드로 만들 수 없다.
     */
    public static Account signUp(String email, String encodedPassword, Role role) {
        AccountStatus status = (role == Role.ORGANIZER) ? AccountStatus.PENDING : AccountStatus.ACTIVE;
        return new Account(email, encodedPassword, role, status);
    }

    /** 운영자가 직접 만드는 ADMIN 계정 (앱 기동 시 초기화용). */
    public static Account createAdmin(String email, String encodedPassword) {
        return new Account(email, encodedPassword, Role.ADMIN, AccountStatus.ACTIVE);
    }

    public boolean isPending() {
        return status == AccountStatus.PENDING;
    }

    public boolean isOrganizer() {
        return role == Role.ORGANIZER;
    }

    /** ADMIN 승인. 이 시점부터 로그인이 가능해진다(decisions.md 12번). */
    public void approve() {
        this.status = AccountStatus.ACTIVE;
    }
}
