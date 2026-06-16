# magicJar 관측 스택 가이드 (Prometheus + Grafana)

ADR 0094 P0-OPS-1·2·6 정합 + ADR 0095 SLO 5건 측정 · backend-core 단일 owner

## 1. 구성 매트릭스

| 레이어 | 컴포넌트 | 위치 |
|------|---------|------|
| 로그 | logback JSON encoder (LogstashEncoder) | `{api,batch,consumer}/src/main/resources/logback-spring.xml` |
| 로그 관제 보조 | Dozzle v10 alerts/webhooks | `docker/docker-compose.yml` `dozzle` + `dozzle_data:/data` |
| 메트릭 노출 | Spring Actuator + Micrometer Prometheus registry | `{api,batch,consumer}/src/main/resources/application.yml` |
| 메트릭 수집 | Prometheus 2.54 | `docker/observability/prometheus/prometheus.yml` |
| 메트릭 시각화 | Grafana 11.2 | `docker/observability/grafana/{provisioning,dashboards}/` |
| 통합 compose | docker-compose.observability.yml | `docker/docker-compose.observability.yml` |

## 2. 기동 방법

### 2.0 한 줄 헬퍼 (권장 — `scripts/observability.sh`)
```bash
scripts/observability.sh up       # Prometheus + Grafana 기동 (-d)
scripts/observability.sh status   # 컨테이너 / healthcheck 상태
scripts/observability.sh down      # 중지 + 컨테이너 제거 (볼륨 유지)
```

`--env-file <절대경로 .env>` 를 강제해 cwd 가 `docker/` 로 바뀌어 `.env` 를 못 찾는
운영 함정 가드 §2 를 회피한다(`build-and-deploy.sh` 와 동일 정책). `up` 후 Prometheus :9090 ·
Grafana :3001 접속 URL + scrape 전제(api·batch·consumer 기동 필요)를 함께 출력한다.

> scrape 대상(api·batch·consumer)이 떠 있어야 metric 이 채워진다 —
> `scripts/build-and-deploy.sh`(docker 통합) 또는 `docker-compose.infra.yml up` + `./gradlew :api:bootRun`(IDE).

### 2.1 관측 스택만 (Prometheus + Grafana) — 수동
```bash
docker compose -f docker/docker-compose.observability.yml --env-file ./.env up -d
```

### 2.2 전체 통합 (인프라 3 + 앱 4 + 관측 2)
```bash
docker compose -f docker/docker-compose.yml -f docker/docker-compose.observability.yml --env-file ./.env up -d
```

### 2.3 IDE bootRun + 관측 스택
```bash
# 인프라 + 관측만 컨테이너
docker compose -f docker/docker-compose.infra.yml -f docker/docker-compose.observability.yml --env-file ./.env up -d

# 호스트에서 3 모듈 직접 실행
./gradlew :api:bootRun
./gradlew :batch:bootRun
./gradlew :consumer:bootRun
```

Prometheus가 자동으로 `host.docker.internal:8080·8081·8082` scrape — IDE 디버깅 친화.

## 3. 접속 URL

| 서비스 | URL | 비고 |
|------|-----|------|
| Prometheus | http://localhost:9090 | scrape targets / metric explorer |
| Grafana | http://localhost:3001 | admin / admin (default — env `GRAFANA_ADMIN_PASSWORD`) |
| Dozzle | http://localhost:8888 | 컨테이너 로그, WARN/ERROR 보조 webhook alert |
| api `/actuator/prometheus` | http://localhost:8080/actuator/prometheus | raw metric scrape |
| batch `/actuator/prometheus` | http://localhost:8081/actuator/prometheus | raw metric scrape |
| consumer `/actuator/prometheus` | http://localhost:8082/actuator/prometheus | raw metric scrape |

포트 매트릭스 (frontend `:3000` 충돌 회피):
- `GRAFANA_HOST_PORT` (default `3001`) — `.env` override 가능
- `PROMETHEUS_HOST_PORT` (default `9090`)

## 4. 로그 매트릭스

### 4.1 profile 분기 (`logback-spring.xml`)
| 프로파일 | encoder | 사용처 |
|--------|--------|------|
| (default — no profile) | PatternLayout (사람 읽기) | 회귀 안전 |
| `dev` | PatternLayout | IDE 콘솔 친화 |
| `prd` | LogstashEncoder (한 줄 JSON) | 운영 검색 / Loki ingestion |

### 4.2 MDC 표준 5 키
- `traceId` — 분산 추적 (ADR 0084 P1-3 — TraceIdServletFilter + TraceIdKafkaRecordInterceptor)
- `spanId` — 단일 span
- `userId` — 인증 사용자
- `orderId` — 주문 dispatch
- `strategyId` — 전략 신호

JSON 출력 시 자동 부착, Plain 출력 시 `[%X{traceId:--}]` 패턴으로 표시.

### 4.3 JSON 로그 예시
```json
{"ts":"2026-05-21T13:24:01.234+09:00","msg":"order dispatched","logger":"kr.spdaewoon.magicjar.consumer.OrderDispatcher","thread":"kafka-consumer-1","level":"INFO","application":"magicjar-consumer","profiles":"prd","traceId":"a1b2c3","orderId":"O-12345"}
```

## 5. 메트릭 매트릭스 (Micrometer)

기본 Spring Boot 노출 + 본 작업 추가:
- `application` 라벨 (= spring.application.name)
- `environment` 라벨 (= SPRING_PROFILES_ACTIVE)

핵심 메트릭:
- `jvm_memory_used_bytes{area="heap"}` — heap 사용량
- `jvm_threads_live_threads` — live thread
- `process_cpu_usage` — process CPU
- `http_server_requests_seconds_count{uri,status}` — api 모듈 HTTP 요청 rate
- `kafka_consumer_*` — Kafka consumer lag·rate (consumer 모듈)

기본 dashboard `magicJar / magicJar — JVM Baseline` — 4 panel (JVM heap·HTTP rate·CPU·thread). Grafana 부팅 직후 자동 등록.

### 5.1 P0-OPS-6 추가 메트릭 (persistence-shared 발행 — ADR 0094 §3-2 P0-OPS-6 + ADR 0095 SLO 5건)

| 메트릭 | 타입 | 소스 | 용도 |
|------|------|------|------|
| `magicjar_signal_to_order_latency_seconds_*` | Timer histogram | `OrderIntentKafkaProducer.publish` (consumer) | ADR 0095 S-1 — signal emit ts → order intent publish ts 측정 (P50·P95·P99) |
| `magicjar_outbox_drain_lag_seconds{outbox=order\|notification}` | Gauge | `OrderEventOutboxRepositoryAdapter.findPending` + `NotificationOutboxRepositoryAdapter.findPending` (persistence-shared) | drain worker fixedDelay 5s 호출 정합 → 가장 오래된 PENDING age (seconds) — 30s 초과 시 SLO 위반 |
| `magicjar_system_events_total{category,severity,action,event_type}` | Counter | `SystemEventLoggerAdapter.emit` (persistence-shared) | ADR 0095 S-2(WS_DISCONNECTED) · S-4(KIS_RATELIMIT) 측정 source — 5분 sliding sum |

### 5.2 자동 노출 (Spring Boot · 추가 wiring 불필요)

- `hikaricp_connections_active`·`hikaricp_connections_idle`·`hikaricp_connections_pending` — HikariCP 자동
- `hibernate.query.execution` — `spring.jpa.properties.hibernate.generate_statistics=true` 시 자동
- `kafka_consumer_records_consumed_total{topic}` — Spring Kafka default (ADR 0095 S-5 `killswitch.engaged.v1` 측정 reuse)
- `http_server_requests_seconds_bucket{uri}` — Spring Actuator default (ADR 0095 S-3 `/api/account/*` 측정 reuse)

### 5.3 SLO dashboard (`magicJar — SLO (ADR 0095)`)

7 panel — Grafana 부팅 직후 자동 등록 (`magicjar-slo.json`):

| panel | SLO | 표현 |
|-------|-----|------|
| S-1 | signal→order latency | timeseries (P50·P95·P99 5m, 임계 5s/10s line) |
| S-2 | KIS WS uptime | gauge (5m window, 임계 95%/99%) |
| S-3 | account API latency | timeseries (P95 5m, 임계 500ms/2s) |
| S-4 | KIS rate limit | stat (5m count, 임계 1/5) |
| S-5 | KillSwitch auto-ON | stat (24h count, 임계 1/3) |
| P0-OPS-6 | Outbox drain lag | timeseries (order·notification, 임계 30s/60s) |
| audit | SystemEvent emit rate | timeseries (category·severity 분포) |

### 5.4 KIS Rate Limit dashboard (`magicJar — KIS Rate Limit`)

ADR 0116 R2 §2-E 의 priority 버킷 관측 metric 시각화 (`magicjar-kis-ratelimit.json`,
uid `magicjar-kis-ratelimit`). Grafana 부팅 직후 `magicJar` 폴더에 자동 등록. KIS 초당 한도(EGW00201/`KIS_TPS_EXCEEDED`)
근접·초과를 주문 path vs 백그라운드 cron 으로 분리해 본다.

| panel | metric | 표현 |
|-------|--------|------|
| 버킷별 토큰 소진율 | `rate(kis_ratelimit_bucket_consumed_total[1m])` by `bucket` | timeseries (user/shared/bg 분리 — 주문 vs 백그라운드 한눈에) |
| 버킷별 누적 소진 | `increase(kis_ratelimit_bucket_consumed_total[5m])` by `bucket` | stacked bars |
| Acquire timeout by priority | `increase(kis_ratelimit_acquire_timeout_total[5m])` by `priority` | timeseries (어느 priority 가 굶었는지 — USER_PATH timeout=주문 지연 직결) |
| TPS 초과 직격 버킷 | `increase(kis_ratelimit_tps_exceeded_total[5m])` by `last_bucket` | stacked bars (EGW00201 직전 소진 버킷 root cause) |
| TPS 초과 총 카운트 | `sum(increase(kis_ratelimit_tps_exceeded_total[5m]))` | stat (임계 1/5 — SLO S-4) |
| SystemEvent KIS_RATELIMIT | `increase(magicjar_system_events_total{event_type="KIS_RATELIMIT"}[5m])` | stat (S-4 source 교차 검증) |

3-tier 버킷 라벨: `user`(주문 USER_PATH) · `shared`(approval·watchlist INGESTION) · `bg`(cron BACKFILL).
`user` 가 지속 포화면 주문 지연 risk, `bg` burst 가 EGW00201 direct hit 면 cron 분산(ADR 0116 §2-D) 점검.
api `/actuator/prometheus` 노출 실증: `kis_ratelimit_bucket_consumed_total{bucket="bg"} 42`·`{bucket="user"} 28`.

## 6. 검증 시나리오

```bash
# 0. 관측 스택 기동 + 헬스
scripts/observability.sh up
curl -s http://localhost:9090/-/healthy          # Prometheus Server is Healthy.
curl -s http://localhost:3001/api/health         # {"database":"ok",...}

# 0.1 rate-limit 대시보드 provisioning 로드 확인
curl -s -u admin:admin http://localhost:3001/api/search | grep kis-ratelimit
# → magicjar-kis-ratelimit | magicJar — KIS Rate Limit

# 0.2 rate-limit metric 수집 확인 (api·consumer·batch 기동 시)
curl -s 'http://localhost:9090/api/v1/query?query=kis_ratelimit_bucket_consumed_total'

# 1. Actuator prometheus 응답 확인 (3 모듈)
curl -s http://localhost:8080/actuator/prometheus | head -5
curl -s http://localhost:8081/actuator/prometheus | head -5
curl -s http://localhost:8082/actuator/prometheus | head -5

# 2. Prometheus targets UP 확인
curl -s http://localhost:9090/api/v1/targets | jq '.data.activeTargets[] | {job: .labels.job, health: .health}'

# 3. Grafana 헬스
curl -s http://localhost:3001/api/health

# 4. JSON 로그 검증 (prd profile 기동 후)
docker logs magicjar-api 2>&1 | tail -5 | jq .
```

## 6.1 Dozzle WARN/ERROR 보조 알림

Dozzle는 운영 이벤트의 권위 source가 아니라 로그 기반 보조 트리거다. 권위 path는 `SystemEvent`와 `notification_outbox`이고, Dozzle은 예상하지 못한 `log.warn`/`log.error` 누락을 잡는 안전망으로 사용한다.

### Destination

Dozzle `Notifications` 화면에서 Slack destination을 1개 등록한다.

- URL: 시스템 설정에 넣은 Slack Incoming Webhook과 같은 URL 사용 가능
- 저장 위치: `dozzle_data:/data`
- Test 버튼으로 Slack 수신 확인

### 권장 Alert 1 — 앱 WARN/ERROR 묶음

Container expression:

```text
name contains "magicjar-api" || name contains "magicjar-consumer" || name contains "magicjar-batch"
```

Log expression:

```text
level == "error" || level == "warn" || message matches "(?i)(ERROR|WARN|Exception|EGW00201|WebSocketClientHandshakeException)"
```

권장 cooldown: 300초 이상. 운영 중 같은 오류가 폭주하면 Dozzle는 묶음 보조 알림, 원인 분석은 Dozzle 로그와 `system_event`를 함께 본다.

### 권장 Alert 2 — 컨테이너 생명주기

Container expression:

```text
name contains "magicjar-"
```

Event expression:

```text
name == "die" || name == "oom" || (name == "health_status" && attributes["health_status"] == "unhealthy")
```

이 알림은 Spring 내부 이벤트를 거치지 못하는 crash/OOM/unhealthy를 잡는다.

## 7. 운영 진입 후속 작업 (ADR 0094 P0-OPS-3·4·5·6)

- P0-OPS-3 — SLO/SLA 정의 + Slack/PagerDuty alertmanager routing — **ADR 0095 Accepted (2026-05-21)**
- P0-OPS-4 — DR/backup 자동화 (pg_dump cron + S3 업로드) — **완료 (PR baseline)**
- P0-OPS-5 — runbook 5 시나리오 (Postgres down·Kafka lag·KIS rate limit·메모리 누수·디스크 가득) — **완료 (49f1bc31)**
- P0-OPS-6 — persistence-shared monitoring (Outbox drain lag · SystemEvent counter · SLO Timer) — **완료 (본 PR — Stage 1 R3)**

## 8. P0 OPS 완료 status (Stage 1 마감)

| ID | gap | 처리 PR | 상태 |
|----|-----|--------|------|
| P0-OPS-1 | logback JSON encoder | #650 (26f83f39) | ✅ |
| P0-OPS-2 | Actuator/Prometheus exporter + Grafana | #650 (26f83f39) | ✅ |
| P0-OPS-3 | SLO/SLA 정의 + alert routing (ADR 0095) | (b89eed6b) | ✅ |
| P0-OPS-4 | DR/backup 자동화 | baseline | ✅ |
| P0-OPS-5 | runbook 5 시나리오 | (49f1bc31) | ✅ |
| P0-OPS-6 | persistence monitoring + SLO source wiring + Grafana | **본 PR** | ✅ |

**Stage 1 P0 OPS 6/6 완료** → **Stage 2 정리 진입 ready** (v3.8.0 운영 baseline P0 마감).
