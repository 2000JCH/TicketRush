package com.ticketrush.ticketrush.domain.reservation.controller;

import com.ticketrush.ticketrush.domain.reservation.service.PaymentWebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** api-design.md 5번 — PG(포트원) 웹훅 수신. 인증 없이 서명 검증만으로 요청 출처를 확인한다. */
@RestController
@RequestMapping("/api/v1/payments/webhook")
@RequiredArgsConstructor
public class PaymentWebhookController {

    private final PaymentWebhookService paymentWebhookService;

    @PostMapping
    public void receive(
            @RequestHeader("webhook-id") String webhookId,
            @RequestHeader("webhook-timestamp") String webhookTimestamp,
            @RequestHeader("webhook-signature") String webhookSignature,
            @RequestBody String rawBody) {
        paymentWebhookService.handle(webhookId, webhookTimestamp, webhookSignature, rawBody);
    }
}
