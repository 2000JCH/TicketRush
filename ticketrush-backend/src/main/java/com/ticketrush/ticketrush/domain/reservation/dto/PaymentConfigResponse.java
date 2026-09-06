package com.ticketrush.ticketrush.domain.reservation.dto;

/**
 * 프론트가 PortOne 브라우저 SDK를 호출할 때 필요한 상점/채널 식별자. 카드(토스페이먼츠)와
 * 간편결제(카카오페이)가 서로 다른 채널이라 채널 키를 결제수단별로 내려준다. 전부 원래
 * 브라우저에 노출되는 공개 식별자이며 시크릿(PORTONE_API_SECRET/WEBHOOK_SECRET)은 포함하지 않는다.
 */
public record PaymentConfigResponse(String storeId, String cardChannelKey, String easyPayChannelKey) {
}
