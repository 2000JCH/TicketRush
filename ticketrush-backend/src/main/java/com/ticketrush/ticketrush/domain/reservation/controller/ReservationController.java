package com.ticketrush.ticketrush.domain.reservation.controller;

import com.ticketrush.ticketrush.domain.reservation.dto.PaymentRequest;
import com.ticketrush.ticketrush.domain.reservation.dto.ReservationResponse;
import com.ticketrush.ticketrush.domain.reservation.service.ReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * api-design.md 5번 결제/예약. 이번 단계는 결제 요청(PAYMENT_REQUESTED 생성)까지만 다룬다 —
 * 실제 PG 호출과 웹훅 수신은 3주차 "결제 연동"에서 구현한다(사용자 확인 완료).
 */
@RestController
@RequestMapping("/api/v1/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationResponse requestPayment(
            @AuthenticationPrincipal Long accountId,
            @RequestHeader(value = "X-Entry-Token", required = false) String entryToken,
            @Valid @RequestBody PaymentRequest request) {
        return reservationService.requestPayment(accountId, entryToken, request);
    }
}
