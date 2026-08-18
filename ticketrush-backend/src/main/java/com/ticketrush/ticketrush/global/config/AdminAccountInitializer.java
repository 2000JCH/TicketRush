package com.ticketrush.ticketrush.global.config;

import com.ticketrush.ticketrush.domain.account.entity.Account;
import com.ticketrush.ticketrush.domain.account.repository.AccountRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADMIN 계정을 앱 기동 시 1개 보장한다.
 * ADMIN은 셀프 가입 대상이 아니지만(db-schema.md 1번), ADMIN이 없으면 ORGANIZER 승인이 불가능해
 * 이벤트 등록까지 연쇄적으로 막히기 때문에 기동 시 자동 생성한다(사용자 확인 완료).
 * 이미 같은 이메일의 계정이 있으면 아무것도 하지 않는다.
 */
@Slf4j
@Component
public class AdminAccountInitializer implements ApplicationRunner {

    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminEmail;
    private final String adminPassword;

    public AdminAccountInitializer(
            AccountRepository accountRepository,
            PasswordEncoder passwordEncoder,
            @Value("${admin.email}") String adminEmail,
            @Value("${admin.password}") String adminPassword) {
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (accountRepository.existsByEmail(adminEmail)) {
            return;
        }
        accountRepository.save(Account.createAdmin(adminEmail, passwordEncoder.encode(adminPassword)));
        log.info("ADMIN 계정을 생성했습니다: {}", adminEmail);
    }
}
