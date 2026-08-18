package com.ticketrush.ticketrush.domain.account.service;

import com.ticketrush.ticketrush.domain.account.dto.LoginRequest;
import com.ticketrush.ticketrush.domain.account.dto.LoginResult;
import com.ticketrush.ticketrush.domain.account.dto.SignupRequest;
import com.ticketrush.ticketrush.domain.account.dto.SignupResponse;
import com.ticketrush.ticketrush.domain.account.entity.Account;
import com.ticketrush.ticketrush.domain.account.entity.Role;
import com.ticketrush.ticketrush.domain.account.repository.AccountRepository;
import com.ticketrush.ticketrush.domain.account.repository.RefreshTokenRepository;
import com.ticketrush.ticketrush.global.exception.BusinessException;
import com.ticketrush.ticketrush.global.exception.ErrorCode;
import com.ticketrush.ticketrush.global.jwt.JwtProvider;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    /** 이메일이 없는 경우와 비밀번호가 틀린 경우를 구분해서 알려주지 않는다(계정 존재 여부 노출 방지). */
    private static final String LOGIN_FAILED_MESSAGE = "이메일 또는 비밀번호가 올바르지 않습니다.";

    private static final String REFRESH_FAILED_MESSAGE = "다시 로그인해주세요.";

    private final AccountRepository accountRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;

    @Transactional
    public SignupResponse signUp(SignupRequest request) {
        if (request.role() == Role.ADMIN) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        if (accountRepository.existsByEmail(request.email())) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        Account account = accountRepository.save(
                Account.signUp(request.email(), passwordEncoder.encode(request.password()), request.role()));

        return new SignupResponse(account.getId(), account.getRole(), account.getStatus());
    }

    @Transactional(readOnly = true)
    public LoginResult login(LoginRequest request) {
        Account account = accountRepository.findByEmail(request.email())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, LOGIN_FAILED_MESSAGE));

        // 비밀번호를 먼저 확인한다 — 순서를 바꾸면 비밀번호를 모르는 사람도
        // ACCOUNT_PENDING 응답으로 "그 이메일의 ORGANIZER 계정이 존재한다"는 걸 알아낼 수 있다.
        if (!passwordEncoder.matches(request.password(), account.getPassword())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, LOGIN_FAILED_MESSAGE);
        }
        if (account.isPending()) {
            throw new BusinessException(ErrorCode.ACCOUNT_PENDING);
        }

        return issueTokens(account);
    }

    /**
     * 쿠키로 받은 Refresh Token을 Redis 저장값과 대조해 Access Token을 재발급한다.
     * 재발급 때마다 Refresh Token도 새로 발급해 덮어쓴다(redis-design.md 9번).
     */
    @Transactional(readOnly = true)
    public LoginResult refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN, REFRESH_FAILED_MESSAGE);
        }

        Long accountId = parseAccountId(refreshToken);

        // Redis에 없거나(로그아웃/만료) 값이 다르면(다른 기기에서 로그인해 덮어써진 경우) 재로그인이 필요하다.
        String storedToken = refreshTokenRepository.findByAccountId(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TOKEN, REFRESH_FAILED_MESSAGE));
        if (!storedToken.equals(refreshToken)) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN, REFRESH_FAILED_MESSAGE);
        }

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TOKEN, REFRESH_FAILED_MESSAGE));

        return issueTokens(account);
    }

    public void logout(Long accountId) {
        refreshTokenRepository.deleteByAccountId(accountId);
    }

    private LoginResult issueTokens(Account account) {
        String accessToken = jwtProvider.createAccessToken(account.getId(), account.getRole().name());
        String refreshToken = jwtProvider.createRefreshToken(account.getId());
        refreshTokenRepository.save(account.getId(), refreshToken);
        return new LoginResult(accessToken, refreshToken);
    }

    private Long parseAccountId(String refreshToken) {
        try {
            return Long.valueOf(jwtProvider.parse(refreshToken).getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN, REFRESH_FAILED_MESSAGE);
        }
    }
}
