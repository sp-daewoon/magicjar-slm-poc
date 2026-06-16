# Realtime Topic Inventory & Naming Proposal

> Status: Accepted (ADR 0125) — 이 문서의 제안표(Recommended Naming Rule + Inventory & Rename Proposal)가 ADR 0125로 권위 승격됨. 2026-06-15 최고 관리자 결재. **Deviation**: DLT는 본 문서 `kafka.{owner}.dlt....v1` infix 제안 대신 `KafkaErrorHandlerConfig`의 `${record.topic()}.DLT` auto-suffix를 유지한다(ADR 0125 §3-3 — error handler 재작성 risk 회피). 마이그레이션은 §Migration Plan의 dual-write가 아닌 **clean cut**으로 수행하며(ADR 0125 §3-2), dual-write 절차는 향후 zero-downtime 운영 단계 표준으로 보존한다. 위치(`/backup`)는 후속 PR에서 `docs/architecture/`로 이동 예정(backend wire 플립과 충돌 회피).
> Date: 2026-06-15 KST
> Scope: runtime source only (`src/main`). `_workspace`, plans, screenshots, test fixtures are excluded.

이 문서는 현재 magicJar의 Kafka topic, STOMP destination, KIS WebSocket `tr_id`를 한곳에 모으고, 다음 개편 때 적용할 수 있는 명명 규칙을 제안한다.

## Summary

| Type | Count | Current shape | Main problem |
|---|---:|---|---|
| Kafka | 41 | `market.tick.v1`, `order.filled.v1`, `system.kis-key-changed.v1` | transport/owner/module이 이름만으로 잘 안 보임 |
| STOMP | 17 patterns | `/topic/market.tick.{code}` | Kafka topic과 비슷해서 fanout인지 source event인지 헷갈림 |
| KIS WebSocket | 7 `tr_id` | `H0UNCNT0`, `H0UNASP0` | 외부 `tr_id`는 의미가 암호처럼 보여 운영 UI에서 맥락 부족 |

## Recommended Naming Rule

### Kafka

```text
kafka.{owner}.{kind}.{domain}.{resource}.{event}.v{version}
```

| Segment | Meaning | Examples |
|---|---|---|
| `kafka` | transport prefix | fixed |
| `owner` | contract owner module | `api`, `consumer`, `batch`, `shared` |
| `kind` | message kind | `evt`, `cmd`, `dlt` |
| `domain` | business area | `market`, `order`, `risk`, `system`, `ai` |
| `resource.event` | what happened or requested | `tick`, `filled`, `manual-trigger` |
| `v{version}` | schema version | `v1` |

Rules:

1. Commands use `cmd`: API/producer가 다른 모듈에 일을 시키는 메시지.
2. Events use `evt`: 이미 발생한 사실을 알리는 메시지.
3. DLT uses `dlt`: suffix `.v1.DLT` 대신 `kafka.{owner}.dlt....v1`.
4. Dynamic keys (`userId`, `code`) stay in record key or payload, not topic name.
5. `owner` is the schema/contract owner. Multiple modules publish one contract이면 `shared`.

### STOMP

```text
/topic/stomp.{owner}.{domain}.{stream}[.{key}]
```

Current `/topic` prefix only tells us broker route. Proposed `stomp.api` makes it obvious that this is API-side WebSocket fanout, not Kafka source.

Rules:

1. Use `/topic/stomp.api...` for API fanout destinations.
2. Put dynamic key at the end: `{code}`, `{userId}`, `{requestId}`, `{jobId}`.
3. Keep user-scoped streams as `.user.{userId}`.
4. Keep symbol/code-scoped streams as final `.{code}` unless a second key exists.

### KIS WebSocket

KIS `tr_id` itself cannot be renamed. Rename only internal display labels, logs, metrics, slot ledger labels.

```text
kisws.consumer.{domain}.{stream}.{variant}.{trId}
```

Example:

```text
kisws.consumer.market.tick.unified.H0UNCNT0
```

## Kafka Inventory & Rename Proposal

### Market

| Current topic | Current owner/path | Type | Proposed topic |
|---|---|---|---|
| `market.tick.v1` | consumer WS/fallback -> API STOMP, order watchers | event | `kafka.consumer.evt.market.tick.v1` |
| `market.orderbook.v1` | consumer KIS WS -> API STOMP | event | `kafka.consumer.evt.market.orderbook.v1` |
| `market.status.v1` | consumer KIS WS -> VI watcher | event | `kafka.consumer.evt.market.status.v1` |
| `market.realtime.demand.v1` | API strategy/screener/daytrade -> consumer demand listener | command | `kafka.api.cmd.market.realtime-demand.v1` |
| `market.index.updated.v1` | batch market index sync | event | `kafka.batch.evt.market.index-updated.v1` |
| `market.sector.updated.v1` | batch sector ranking sync | event | `kafka.batch.evt.market.sector-updated.v1` |

### Candle

| Current topic | Current owner/path | Type | Proposed topic |
|---|---|---|---|
| `market.candle.minute.closed.v1` | consumer 1m aggregator -> API STOMP/signal | event | `kafka.consumer.evt.market.candle.1m.closed.v1` |
| `market.candle.3m.closed.v1` | consumer boundary aggregator -> API STOMP/signal | event | `kafka.consumer.evt.market.candle.3m.closed.v1` |
| `market.candle.5m.closed.v1` | consumer boundary aggregator -> API STOMP/signal | event | `kafka.consumer.evt.market.candle.5m.closed.v1` |
| `market.candle.15m.closed.v1` | consumer boundary aggregator -> API STOMP/signal | event | `kafka.consumer.evt.market.candle.15m.closed.v1` |
| `market.candle.30m.closed.v1` | consumer boundary aggregator -> API STOMP/signal | event | `kafka.consumer.evt.market.candle.30m.closed.v1` |
| `market.candle.90m.closed.v1` | consumer boundary aggregator -> API STOMP/signal | event | `kafka.consumer.evt.market.candle.90m.closed.v1` |
| `market.candle.1h.closed.v1` | consumer boundary aggregator -> API STOMP/signal | event | `kafka.consumer.evt.market.candle.1h.closed.v1` |
| `market.candle.4h.closed.v1` | consumer boundary/daily finalizer -> API STOMP/signal | event | `kafka.consumer.evt.market.candle.4h.closed.v1` |
| `market.candle.12h.closed.v1` | consumer boundary/daily finalizer -> API STOMP/signal | event | `kafka.consumer.evt.market.candle.12h.closed.v1` |

### Trading, Risk, Reconcile

| Current topic | Current owner/path | Type | Proposed topic |
|---|---|---|---|
| `signal.generated.v1` | consumer signal engine -> risk/API STOMP | event | `kafka.consumer.evt.strategy.signal-generated.v1` |
| `risk.violation.v1` | consumer risk gate -> notify/API STOMP | event | `kafka.consumer.evt.risk.violation.v1` |
| `order.intent.v1` | shared: API manual + consumer risk -> consumer order router | command | `kafka.shared.cmd.order.intent.v1` |
| `order.requested.v1` | consumer order router -> notify/API STOMP | event | `kafka.consumer.evt.order.requested.v1` |
| `order.filled.v1` | consumer execution listener -> notify/journal/API STOMP | event | `kafka.consumer.evt.order.filled.v1` |
| `order.rejected.v1` | consumer order router -> notify/API STOMP | event | `kafka.consumer.evt.order.rejected.v1` |
| `order.cancelled.v1` | consumer execution/cancel path -> notify/API STOMP | event | `kafka.consumer.evt.order.cancelled.v1` |
| `account.balance.changed.v1` | consumer order/account path -> API account STOMP | event | `kafka.consumer.evt.account.balance-changed.v1` |
| `reconcile.run.v1` | consumer reconcile publisher -> notify/API STOMP | event | `kafka.consumer.evt.reconcile.run.v1` |
| `reconcile.drift.v1` | consumer reconcile -> kill switch/notify/API STOMP | event | `kafka.consumer.evt.reconcile.drift.v1` |
| `reconcile.manual.trigger.v1` | API manual trigger -> consumer reconcile | command | `kafka.api.cmd.reconcile.manual-trigger.v1` |
| `killswitch.engaged.v1` | shared: API/consumer kill switch paths -> notify/API STOMP | event | `kafka.shared.evt.killswitch.engaged.v1` |

### System, Data, AI

| Current topic | Current owner/path | Type | Proposed topic |
|---|---|---|---|
| `system.event.v1` | shared persistence event logger -> API STOMP/notify | event | `kafka.shared.evt.system.event.v1` |
| `system.kis-key-changed.v1` | API credential change -> consumer reconnect | event | `kafka.api.evt.system.kis-key.changed.v1` |
| `watchlist.changed.v1` | API watchlist -> consumer WS subscription | event | `kafka.api.evt.watchlist.changed.v1` |
| `symbol.detail.command.v1` | API symbol detail enter/exit -> consumer realtime slots | command | `kafka.api.cmd.symbol.detail-realtime.v1` |
| `instrument.updated.v1` | batch instrument sync | event | `kafka.batch.evt.instrument.updated.v1` |
| `corp_action.applied.v1` | batch corp action -> candle adjustment | event | `kafka.batch.evt.corporate-action.applied.v1` |
| `disclosure.new.v1` | API DART polling | event | `kafka.api.evt.disclosure.new.v1` |
| `news.collected.v1` | API Naver news polling | event | `kafka.api.evt.news.collected.v1` |
| `ai.comment.requested.v1` | API AI explain request -> consumer AI dispatcher | command | `kafka.api.cmd.ai.comment-requested.v1` |
| `ai.comment.responded.v1` | consumer AI dispatcher -> API STOMP/audit | event | `kafka.consumer.evt.ai.comment-responded.v1` |

### DLT

| Current topic | Proposed topic |
|---|---|
| `order.intent.v1.DLT` | `kafka.shared.dlt.order.intent.v1` |
| `order.requested.v1.DLT` | `kafka.consumer.dlt.order.requested.v1` |
| `order.rejected.v1.DLT` | `kafka.consumer.dlt.order.rejected.v1` |
| `order.filled.v1.DLT` | `kafka.consumer.dlt.order.filled.v1` |

## STOMP Inventory & Rename Proposal

| Current destination | Source | Proposed destination |
|---|---|---|
| `/topic/market.tick.{code}` | Kafka `market.tick.v1` | `/topic/stomp.api.market.tick.{code}` |
| `/topic/market.orderbook.{code}` | Kafka `market.orderbook.v1` | `/topic/stomp.api.market.orderbook.{code}` |
| `/topic/market.candle.minute.{code}` | Kafka `market.candle.minute.closed.v1` | `/topic/stomp.api.market.candle.1m.{code}` |
| `/topic/market.candle.{interval}.{code}` | Kafka boundary candle topics | `/topic/stomp.api.market.candle.{interval}.{code}` |
| `/topic/order.user.{userId}` | Kafka `order.*.v1` | `/topic/stomp.api.order.user.{userId}` |
| `/topic/signal.user.{userId}` | Kafka `signal.generated.v1` | `/topic/stomp.api.signal.user.{userId}` |
| `/topic/risk.user.{userId}` | Kafka `risk.violation.v1` | `/topic/stomp.api.risk.user.{userId}` |
| `/topic/recon.user.{userId}` | Kafka reconcile/kill switch topics | `/topic/stomp.api.reconcile.user.{userId}` |
| `/topic/account.balance.{userId}` | Kafka `account.balance.changed.v1` | `/topic/stomp.api.account.balance.user.{userId}` |
| `/topic/system.event.user.{userId}` | Kafka `system.event.v1` | `/topic/stomp.api.system.event.user.{userId}` |
| `/topic/system.event.broadcast` | Kafka `system.event.v1` | `/topic/stomp.api.system.event.broadcast` |
| `/topic/ai.explain.{requestId}` | Kafka `ai.comment.responded.v1` | `/topic/stomp.api.ai.explain.{requestId}` |
| `/topic/market.index.{code}` | market trend notifier | `/topic/stomp.api.market.index.{code}` |
| `/topic/market.sector.rank` | market trend notifier | `/topic/stomp.api.market.sector.rank` |
| `/topic/market.user.{userId}.pref` | trend preference update | `/topic/stomp.api.market.preference.user.{userId}` |
| `/topic/candle.recollect.{code}.{jobId}` | candle recollect job service | `/topic/stomp.api.job.candle-recollect.{code}.{jobId}` |

## KIS WebSocket Inventory & Alias Proposal

| KIS `tr_id` | Meaning | Current handling | Proposed internal alias |
|---|---|---|---|
| `H0UNCNT0` | KRX+NXT unified tick | active subscription | `kisws.consumer.market.tick.unified.H0UNCNT0` |
| `H0UNASP0` | KRX+NXT unified orderbook | active subscription | `kisws.consumer.market.orderbook.unified.H0UNASP0` |
| `H0UNMKO0` | unified market status / VI | active subscription | `kisws.consumer.market.status-vi.unified.H0UNMKO0` |
| `H0STOUP0` | overtime single-price tick | active subscription during overtime | `kisws.consumer.market.tick.overtime.H0STOUP0` |
| `H0STCNI0` | real execution notice | active in REAL mode | `kisws.consumer.order.execution.real.H0STCNI0` |
| `H0STCNI9` | mock execution notice | active in MOCK mode | `kisws.consumer.order.execution.mock.H0STCNI9` |
| `H0STCNT0` | legacy/base KRX tick parser reference | parser/comment compatibility | `kisws.consumer.market.tick.krx-legacy.H0STCNT0` |

## Migration Plan

1. Add a central topic registry first.
   - Backend: `KafkaTopics`, `StompDestinations`, `KisWsChannels`.
   - Frontend: `stompDestinations.ts`.
   - Goal: no raw string except registry and tests.
2. Add new Kafka topics with dual-write.
   - Producers publish old and new topic for one release window.
   - Consumers listen to both old and new, with idempotency where needed.
   - Monitor lag and DLT separately.
3. Cut consumers to new topics.
   - Stop old-topic consumption after lag is zero.
   - Keep old producers for rollback for one extra release.
4. Cut producers to new topics.
   - Remove dual-write.
   - Keep old `NewTopic` definitions for retention window only.
5. STOMP migration is client-facing.
   - API can fanout old and new destinations in parallel.
   - Frontend subscribes to new destination first, old destination fallback for one release.
6. KIS WS is alias-only.
   - Do not change outbound KIS `tr_id`.
   - Change logs, metrics, slot ledger labels, runtime status UI labels.

## Recommended Next Step

Do not rename all topics in one PR. Start with a registry-only PR:

1. Create constants for current names.
2. Replace raw strings with constants.
3. Add proposed names as comments or `nextName` metadata.
4. Then migrate one domain at a time: market -> candle -> order/risk -> system -> AI/news.

## Source Files Checked

| Area | Files |
|---|---|
| Kafka topic beans | `consumer/config/KafkaTopicConfig.kt`, `consumer/config/OrderTopicConfig.kt`, `consumer/reconcile/ReconcileTopicConfig.kt` |
| Kafka producers/listeners | `api/src/main`, `consumer/src/main`, `batch/src/main`, `persistence-shared/src/main` |
| STOMP broker/fanout | `api/config/WebSocketConfig.kt`, `api/messaging/*`, `api/service/CandleRecollectJobService.kt` |
| Frontend STOMP subscribe | `frontend/src/lib/stomp.ts`, `frontend/src/api/hooks/*`, `frontend/src/pages/Symbol/*` |
| KIS WebSocket | `consumer/ws/KisWebSocketBridge.kt`, `consumer/ws/KisWsMessageParser.kt`, `consumer/order/KisExecutionWsListener.kt` |
