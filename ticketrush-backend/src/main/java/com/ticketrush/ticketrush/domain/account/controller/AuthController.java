package com.ticketrush.ticketrush.domain.account.controller;

import com.ticketrush.ticketrush.domain.account.dto.LoginRequest;
import com.ticketrush.ticketrush.domain.account.dto.LoginResponse;
import com.ticketrush.ticketrush.domain.account.dto.LoginResult;
import com.ticketrush.ticketrush.domain.account.dto.SignupRequest;
import com.ticketrush.ticketrush.domain.account.dto.SignupResponse;
import com.ticketrush.ticketrush.domain.account.service.AuthService;
import com.ticketrush.ticketrush.global.jwt.RefreshTokenCookieFactory;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** api-design.md 1번 인증(Auth). */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenCookieFactory refreshTokenCookieFactory;

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public SignupResponse signUp(@Valid @RequestBody SignupRequest request) {
        return authService.signUp(request);
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return withRefreshTokenCookie(authService.login(request));
    }

    /** Access Token 재발급. 인증 헤더가 아니라 httpOnly Cookie의 Refresh Token으로 검증한다. */
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(
            @CookieValue(name = RefreshTokenCookieFactory.COOKIE_NAME, required = false) String refreshToken) {
        return withRefreshTokenCookie(authService.refresh(refreshToken));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal Long accountId) {
        authService.logout(accountId);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookieFactory.expire().toString())
                .build();
    }

    /** Refresh Token은 응답 바디가 아니라 Set-Cookie로만 내보낸다(decisions.md 3번). */
    private ResponseEntity<LoginResponse> withRefreshTokenCookie(LoginResult result) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        refreshTokenCookieFactory.create(result.refreshToken()).toString())
                .body(new LoginResponse(result.accessToken()));
    }
}
