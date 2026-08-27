package com.ticketrush.ticketrush.domain.reservation.consumer;

import com.ticketrush.ticketrush.domain.reservation.entity.OutboxEvent;
import com.ticketrush.ticketrush.domain.reservation.service.ReservationService;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Kafka exactly-once(decisions.md 6번) 파이프라인의 소비자 — outbox_events에 쓰인 행을
 * Debezium(Outbox Event Router SMT)이 이 토픽으로 발행하면, decisions.md 5번 Choreography의
 * 보상 2단계(PAYMENT_FAILED → SEAT_RELEASED)를 여기서 트리거한다
 * ({@link ReservationService#markPaymentFailed} Javadoc 참고).
 *
 * <p>Kafka/Debezium은 최소 한 번(at-least-once) 전달만 보장하고, 이 컨슈머가 호출하는
 * {@link ReservationService#releaseAfterFailure}는 이미 예약 상태(status == PAYMENT_FAILED)를
 * 확인하는 멱등 처리라 같은 이벤트가 재전달돼도 안전하다 — "at-least-once 전달 + 멱등 소비자 =
 * 결과적으로 정확히 한 번 처리"인 조합으로 exactly-once 효과를 얻는다. decisions.md 6번이 말하는
 * 진짜 Kafka 트랜잭션 API(consume-transform-produce)는 이 컨슈머처럼 재발행(produce) 단계가 없는
 * 흐름에는 해당하지 않는다 — 재발행이 필요한 기능(정산/알림)이 생기면 그때 적용한다.
 *
 * <p>처리 중 발생한 예외를 여기서 삼키지 않는다 — Spring Kafka 기본 에러 핸들러가 재시도하도록
 * 둬야 DB 순간 장애 같은 일시적 문제에서 카오스 테스트(decisions.md 8번)가 기대하는 "장애 후
 * 자동 회복"이 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentFailedConsumer {

    private final ReservationService reservationService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "${reservation.outbox.kafka-topic}")
    public void onMessage(ConsumerRecord<String, String> record) {
        String eventType = extractEventType(record);
        if (!OutboxEvent.EVENT_TYPE_PAYMENT_FAILED.equals(eventType)) {
            log.debug("처리 대상이 아닌 이벤트 타입({}) — 무시", eventType);
            return;
        }

        Long reservationId = objectMapper.readTree(record.value()).get("reservationId").asLong();
        log.info("PAYMENT_FAILED 이벤트 수신 — 예약 {} 좌석 반납 처리", reservationId);
        reservationService.releaseAfterFailure(reservationId);
    }

    private String extractEventType(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader("eventType");
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
