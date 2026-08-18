package com.ticketrush.ticketrush.domain.account.dto;

/**
 * 서비스가 컨트롤러에 넘기는 내부 결과값(API 응답 형식이 아니다).
 * accessToken은 응답 바디로, refreshToken은 httpOnly Cookie로 나가기 때문에 전달 경로가 갈린다.
 */
public record LoginResult(String accessToken, String refreshToken) {
}
