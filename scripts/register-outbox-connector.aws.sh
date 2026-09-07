#!/bin/bash
# AWS 배포용 Debezium Outbox 커넥터 등록 (register-outbox-connector.ps1 의 AWS 버전).
# 로컬 버전과 차이: DB가 RDS라 hostname/user/password를 환경변수에서 읽는다.
# EC2에서 `docker compose -f docker-compose.aws.yml up -d` 이후 한 번 실행.
#   RDS_ENDPOINT=... RDS_PASSWORD=... bash scripts/register-outbox-connector.aws.sh
# (레포 루트 .env 에 두 값이 있으면 `set -a; . ./.env; set +a` 후 실행)
set -euo pipefail

CONNECT_URL="http://localhost:8083"
NAME="ticketrush-outbox-connector"
: "${RDS_ENDPOINT:?RDS_ENDPOINT 필요}"
: "${RDS_USERNAME:=admin}"
: "${RDS_PASSWORD:?RDS_PASSWORD 필요}"

echo "Kafka Connect 대기..."
for i in $(seq 1 20); do
  curl -sf "$CONNECT_URL/connectors" >/dev/null 2>&1 && break
  [ "$i" = 20 ] && { echo "Kafka Connect 연결 실패"; exit 1; }
  sleep 3
done

if curl -sf "$CONNECT_URL/connectors/$NAME" >/dev/null 2>&1; then
  echo "커넥터 '$NAME' 이미 등록됨. 건너뜀."
  exit 0
fi

curl -s -X POST "$CONNECT_URL/connectors" -H 'Content-Type: application/json' -d "$(cat <<JSON
{
  "name": "$NAME",
  "config": {
    "connector.class": "io.debezium.connector.mysql.MySqlConnector",
    "database.hostname": "$RDS_ENDPOINT",
    "database.port": "3306",
    "database.user": "$RDS_USERNAME",
    "database.password": "$RDS_PASSWORD",
    "database.server.id": "184054",
    "topic.prefix": "ticketrush",
    "database.include.list": "ticketrush",
    "table.include.list": "ticketrush.outbox_events",
    "tombstones.on.delete": "false",
    "include.schema.changes": "false",
    "transforms": "outbox",
    "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
    "transforms.outbox.route.by.field": "aggregate_type",
    "transforms.outbox.route.topic.replacement": "ticketrush.\${routedByValue}.events",
    "transforms.outbox.table.field.event.id": "id",
    "transforms.outbox.table.field.event.key": "aggregate_id",
    "transforms.outbox.table.field.event.timestamp": "created_at",
    "transforms.outbox.table.field.event.payload": "payload",
    "transforms.outbox.table.expand.json.payload": "true",
    "transforms.outbox.table.fields.additional.placement": "event_type:header:eventType",
    "key.converter": "org.apache.kafka.connect.json.JsonConverter",
    "key.converter.schemas.enable": "false",
    "value.converter": "org.apache.kafka.connect.json.JsonConverter",
    "value.converter.schemas.enable": "false",
    "schema.history.internal.kafka.topic": "schema-history.ticketrush",
    "schema.history.internal.kafka.bootstrap.servers": "kafka:29092"
  }
}
JSON
)" | python3 -m json.tool
echo "커넥터 등록 완료 → 토픽 ticketrush.reservation.events"
