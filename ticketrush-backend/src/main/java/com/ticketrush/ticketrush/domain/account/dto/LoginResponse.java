package com.ticketrush.ticketrush.domain.account.dto;

/**
 * api-design.md 1번의 로그인 성공 응답.
 * Refresh Token은 이 바디가 아니라 httpOnly Cookie로 내려간다(다음 단계에서 구현).
 */
public record LoginResponse(String accessToken) {
}
