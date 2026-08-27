package com.ticketrush.ticketrush.domain.reservation.controller;

import com.ticketrush.ticketrush.domain.reservation.dto.PaymentRequest;
import com.ticketrush.ticketrush.domain.reservation.dto.ReservationDetailResponse;
import com.ticketrush.ticketrush.domain.reservation.dto.ReservationResponse;
import com.ticketrush.ticketrush.domain.reservation.service.ReservationService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** api-design.md 5번 결제/예약. PG 웹훅 수신은 PaymentWebhookController가 별도로 다룬다. */
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

    @GetMapping("/me")
    public List<ReservationDetailResponse> findMyReservations(@AuthenticationPrincipal Long accountId) {
        return reservationService.findMyReservations(accountId);
    }

    @GetMapping("/{reservationId}")
    public ReservationDetailResponse findDetail(
            @AuthenticationPrincipal Long accountId, @PathVariable Long reservationId) {
        return reservationService.findDetail(accountId, reservationId);
    }

    @PostMapping("/{reservationId}/cancel")
    public ReservationDetailResponse cancel(
            @AuthenticationPrincipal Long accountId, @PathVariable Long reservationId) {
        return reservationService.cancel(accountId, reservationId);
    }
}
