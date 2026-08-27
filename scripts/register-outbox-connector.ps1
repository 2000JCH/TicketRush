# Debezium Outbox 커넥터를 Kafka Connect에 등록한다 (decisions.md 6번 Outbox 패턴,
# db-schema.md 7번 outbox_events). `docker compose up -d` 이후 한 번 실행하면 된다 — 멱등이라
# 이미 등록돼 있으면 그대로 건너뛴다. Kafka Connect 컨테이너 기동 직후에는 REST API가 아직 안
# 뜬 상태일 수 있어, 준비될 때까지 잠깐 재시도한다.
$ErrorActionPreference = "Stop"

$connectUrl = "http://localhost:8083"
$connectorName = "ticketrush-outbox-connector"

for ($i = 0; $i -lt 20; $i++) {
    try {
        Invoke-RestMethod -Uri "$connectUrl/connectors" -Method Get -TimeoutSec 3 | Out-Null
        break
    } catch {
        if ($i -eq 19) { throw "Kafka Connect($connectUrl)에 연결할 수 없습니다. docker compose up -d로 먼저 기동했는지 확인하세요." }
        Start-Sleep -Seconds 3
    }
}

$existing = $null
try {
    $existing = Invoke-RestMethod -Uri "$connectUrl/connectors/$connectorName" -Method Get
} catch {
    $existing = $null
}

if ($existing) {
    Write-Output "커넥터 '$connectorName'가 이미 등록되어 있습니다. 건너뜁니다."
    exit 0
}

# outbox_events(aggregate_type/aggregate_id/event_type/payload/created_at)를 표준 Debezium
# Outbox 컬럼명(aggregatetype/aggregateid/type/payload/timestamp)에 명시적으로 매핑한다.
# route.by.field=aggregate_type 값("reservation")별로 토픽이 나뉘고, event_type은 Kafka
# 헤더(eventType)로 실려 나간다.
$body = @{
    name   = $connectorName
    config = @{
        "connector.class"                                    = "io.debezium.connector.mysql.MySqlConnector"
        "database.hostname"                                   = "mysql"
        "database.port"                                       = "3306"
        "database.user"                                       = "root"
        "database.password"                                   = "root"
        "database.server.id"                                  = "184054"
        "topic.prefix"                                        = "ticketrush"
        "database.include.list"                                = "ticketrush"
        "table.include.list"                                   = "ticketrush.outbox_events"
        "tombstones.on.delete"                                 = "false"
        # 스키마 변경(DDL) 이벤트를 topic.prefix와 같은 이름("ticketrush")의 토픽에 발행하려는
        # 기본 동작을 끈다 — 우리는 DDL 변경을 구독할 일이 없고, 이 브로커는
        # auto.create.topics.enable=false라 그 토픽이 없으면 발행이 계속 실패해 커넥터 태스크
        # 전체가 멈춘다(실제로 겪은 문제, 2026-08-27).
        "include.schema.changes"                               = "false"
        "transforms"                                           = "outbox"
        "transforms.outbox.type"                               = "io.debezium.transforms.outbox.EventRouter"
        "transforms.outbox.route.by.field"                     = "aggregate_type"
        "transforms.outbox.route.topic.replacement"            = 'ticketrush.${routedByValue}.events'
        "transforms.outbox.table.field.event.id"               = "id"
        "transforms.outbox.table.field.event.key"              = "aggregate_id"
        "transforms.outbox.table.field.event.timestamp"        = "created_at"
        "transforms.outbox.table.field.event.payload"          = "payload"
        "transforms.outbox.table.expand.json.payload"          = "true"
        "transforms.outbox.table.fields.additional.placement"  = "event_type:header:eventType"
        "key.converter"                                        = "org.apache.kafka.connect.json.JsonConverter"
        "key.converter.schemas.enable"                         = "false"
        "value.converter"                                      = "org.apache.kafka.connect.json.JsonConverter"
        "value.converter.schemas.enable"                       = "false"
        "schema.history.internal.kafka.topic"                  = "schema-history.ticketrush"
        "schema.history.internal.kafka.bootstrap.servers"      = "kafka:29092"
    }
} | ConvertTo-Json -Depth 5

$response = Invoke-RestMethod -Uri "$connectUrl/connectors" -Method Post -Body $body -ContentType "application/json"
Write-Output "커넥터 등록 완료: $($response.name) → 토픽 ticketrush.reservation.events"
