package com.ticketrush.ticketrush.domain.reservation.controller;

import com.ticketrush.ticketrush.domain.reservation.dto.PaymentConfigResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 프론트 PortOne SDK 연동용 설정 조회 — 로그인한 사용자가 결제창을 띄우기 직전에 호출한다. */
@RestController
@RequestMapping("/api/v1/payments/config")
public class PaymentConfigController {

    private final PaymentConfigResponse config;

    public PaymentConfigController(
            @Value("${portone.store-id}") String storeId,
            @Value("${portone.channel-key.card}") String cardChannelKey,
            @Value("${portone.channel-key.easypay}") String easyPayChannelKey) {
        this.config = new PaymentConfigResponse(storeId, cardChannelKey, easyPayChannelKey);
    }

    @GetMapping
    public PaymentConfigResponse get() {
        return config;
    }
}
