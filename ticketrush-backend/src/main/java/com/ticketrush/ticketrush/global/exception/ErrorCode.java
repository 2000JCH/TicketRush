package com.ticketrush.ticketrush.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * api-design.md "에러 응답 형식"의 에러 코드 표를 그대로 옮긴 것.
 * 아직 구현되지 않은 도메인(좌석/결제 등)의 코드는 해당 기능을 만들 때 추가한다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다."),
    // 로그인 실패와 "인증 없이 보호된 경로 접근"에 모두 쓰이므로 기본 메시지는 중립적으로 둔다.
    // 로그인 실패 시에는 AuthService가 더 구체적인 메시지를 덧붙인다.
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    ACCOUNT_PENDING(HttpStatus.FORBIDDEN, "관리자 승인 대기 중입니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "계정을 찾을 수 없습니다."),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
    ACCOUNT_ALREADY_APPROVED(HttpStatus.CONFLICT, "이미 승인된 계정입니다."),
    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "이벤트를 찾을 수 없습니다."),
    EVENT_ALREADY_OPENED(HttpStatus.CONFLICT, "이미 예매가 시작된 이벤트는 수정하거나 삭제할 수 없습니다."),
    QUEUE_ENTRY_NOT_FOUND(HttpStatus.NOT_FOUND, "대기열에 진입한 기록이 없습니다. 다시 진입해주세요."),
    ENTRY_TOKEN_REQUIRED(HttpStatus.UNAUTHORIZED, "입장 토큰이 필요합니다."),
    ENTRY_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "입장 토큰이 만료되었습니다. 대기열에 다시 진입해주세요."),
    SEAT_NOT_FOUND(HttpStatus.NOT_FOUND, "좌석을 찾을 수 없습니다."),
    SEAT_ALREADY_HELD(HttpStatus.CONFLICT, "이미 선택된 좌석입니다."),
    STANDING_SOLD_OUT(HttpStatus.CONFLICT, "매진되었습니다."),
    ACTIVE_RESERVATION_EXISTS(HttpStatus.CONFLICT, "이미 진행 중인 예매가 있습니다."),
    QUANTITY_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "1인당 최대 2매까지 구매 가능합니다."),
    GROUP_HOLD_LOCK_TIMEOUT(HttpStatus.CONFLICT, "좌석 선택이 몰려 처리하지 못했습니다. 잠시 후 다시 시도해주세요."),
    ACTIVE_HOLD_NOT_FOUND(HttpStatus.NOT_FOUND, "결제를 요청할 좌석 홀드가 없습니다. 다시 선택해주세요."),
    DUPLICATE_PAYMENT_REQUEST(HttpStatus.CONFLICT, "이미 처리 중인 결제 요청입니다."),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "예약을 찾을 수 없습니다."),
    RESERVATION_NOT_CANCELLABLE(HttpStatus.CONFLICT, "결제 완료된 예약만 취소할 수 있습니다."),
    INVALID_WEBHOOK_SIGNATURE(HttpStatus.UNAUTHORIZED, "웹훅 서명이 유효하지 않습니다."),
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "요청 입력값이 올바르지 않습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;
}
