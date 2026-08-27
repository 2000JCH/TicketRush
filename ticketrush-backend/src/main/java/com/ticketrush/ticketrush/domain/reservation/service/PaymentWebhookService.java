package com.ticketrush.ticketrush.domain.reservation.service;

import com.ticketrush.ticketrush.domain.reservation.entity.Reservation;
import com.ticketrush.ticketrush.domain.reservation.repository.ReservationRepository;
import com.ticketrush.ticketrush.global.exception.BusinessException;
import com.ticketrush.ticketrush.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 포트원(V2) 웹훅 수신 처리 — 서명 검증 후 {@code type} 필드로 확정/실패 전이를 트리거한다
 * (api-design.md 5번). 웹훅 멱등성은 별도 장치 없이 {@link ReservationService#confirmPayment}/
 * {@link ReservationService#markPaymentFailed}가 이미 갖고 있는 상태 체크(decisions.md 5번,
 * "이미 처리된 요청" early return)로 처리한다 — PG가 같은 이벤트를 중복 전송해도 안전하다.
 *
 * <p><b>서명 검증 방식(가정, 실제 검증 미완료)</b>: Standard Webhooks 스펙(webhook-id/
 * webhook-timestamp/webhook-signature 헤더 + HMAC-SHA256, secret은 "whsec_" 접두사 + base64)을
 * 따른다고 가정했다 — 1주차 스모크테스트 로그에 정확히 이 스펙의 헤더 이름(webhook-signature)이
 * 찍혔던 걸 근거로 삼았다(progress.md 참고). 다만 웹훅 시크릿을 아직 콘솔에서 찾지 못해 실제
 * 서명으로 검증해본 적은 없다 — 시크릿을 발급받으면 반드시 실서명으로 재검증할 것.
 *
 * <p><b>단순화(알려진 한계)</b>: 웹훅 body의 값을 그대로 신뢰해 상태를 확정한다. 더 엄격하게
 * 하려면 포트원 결제 조회 API(GetPayment)로 서버 대 서버 재검증을 해야 하지만, 지금은 실제
 * 결제 채널(카드/카카오페이) 프론트 연동이 없어 검증할 방법이 없다 — 프론트 PG SDK 연동이
 * 이어지는 시점에 함께 보강한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentWebhookService {

    private static final String TYPE_PAID = "Transaction.Paid";
    private static final String TYPE_FAILED = "Transaction.Failed";
    private static final long TIMESTAMP_TOLERANCE_SECONDS = 300;
    private static final String SECRET_PREFIX = "whsec_";

    private final ReservationRepository reservationRepository;
    private final ReservationService reservationService;
    private final ObjectMapper objectMapper;

    @Value("${portone.webhook-secret}")
    private String webhookSecret;

    public void handle(String webhookId, String webhookTimestamp, String webhookSignature, String rawBody) {
        verifySignature(webhookId, webhookTimestamp, webhookSignature, rawBody);

        JsonNode root = objectMapper.readTree(rawBody);
        String type = root.path("type").asString(null);
        String paymentId = root.path("data").path("paymentId").asString(null);
        if (paymentId == null) {
            log.warn("웹훅 payload에 data.paymentId가 없습니다: type={}", type);
            return;
        }

        Reservation reservation = reservationRepository.findByPgPaymentId(paymentId).orElse(null);
        if (reservation == null) {
            log.warn("paymentId({})에 해당하는 예약을 찾을 수 없습니다.", paymentId);
            return;
        }

        if (TYPE_PAID.equals(type)) {
            reservationService.confirmPayment(reservation.getId());
        } else if (TYPE_FAILED.equals(type)) {
            reservationService.markPaymentFailed(reservation.getId());
        } else {
            log.info("처리 대상이 아닌 웹훅 타입({}) — 무시", type);
        }
    }

    private void verifySignature(String webhookId, String webhookTimestamp, String webhookSignature, String rawBody) {
        // 빈 시크릿으로 검증을 통과시키면 인증을 꺼둔 것과 같아 더 위험하다 — 미발급 상태에서는
        // 무조건 거절한다(포트원 콘솔 "호출 테스트"처럼 서명 헤더가 없는 요청도 여기서 걸러진다).
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_WEBHOOK_SIGNATURE);
        }
        if (webhookId == null || webhookTimestamp == null || webhookSignature == null) {
            throw new BusinessException(ErrorCode.INVALID_WEBHOOK_SIGNATURE);
        }
        if (!isTimestampFresh(webhookTimestamp)) {
            throw new BusinessException(ErrorCode.INVALID_WEBHOOK_SIGNATURE);
        }

        String expected = sign(webhookId, webhookTimestamp, rawBody);
        boolean valid = Arrays.stream(webhookSignature.trim().split("\\s+"))
                .map(PaymentWebhookService::stripVersionPrefix)
                .anyMatch(candidate -> MessageDigest.isEqual(
                        candidate.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8)));
        if (!valid) {
            throw new BusinessException(ErrorCode.INVALID_WEBHOOK_SIGNATURE);
        }
    }

    private static String stripVersionPrefix(String token) {
        int comma = token.indexOf(',');
        return comma < 0 ? token : token.substring(comma + 1);
    }

    /** 재전송 공격 방지 — 타임스탬프가 5분 이상 벗어난 요청은 서명이 맞아도 거절한다. */
    private boolean isTimestampFresh(String webhookTimestamp) {
        try {
            long timestampSeconds = Long.parseLong(webhookTimestamp);
            return Math.abs(Instant.now().getEpochSecond() - timestampSeconds) <= TIMESTAMP_TOLERANCE_SECONDS;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String sign(String webhookId, String webhookTimestamp, String rawBody) {
        try {
            String secretKeyPart = webhookSecret.startsWith(SECRET_PREFIX)
                    ? webhookSecret.substring(SECRET_PREFIX.length())
                    : webhookSecret;
            byte[] key = Base64.getDecoder().decode(secretKeyPart);
            String signedContent = webhookId + "." + webhookTimestamp + "." + rawBody;

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] digest = mac.doFinal(signedContent.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("웹훅 서명 계산 실패", e);
        }
    }
}
