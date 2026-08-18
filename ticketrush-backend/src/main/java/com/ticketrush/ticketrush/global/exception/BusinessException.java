package com.ticketrush.ticketrush.global.exception;

import lombok.Getter;

/** 비즈니스 규칙 위반. GlobalExceptionHandler가 ErrorCode의 상태/코드로 변환해 응답한다. */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** 같은 코드라도 상황에 따라 더 구체적인 안내가 필요할 때 쓴다. */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
