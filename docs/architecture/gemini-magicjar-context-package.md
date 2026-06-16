# Gemini Context Package for magicJar

> 목적: Gemini에게 magicJar를 "자동매매 결정을 내리는 AI"가 아니라, **전략 빌더·종목 발굴·전략 리서치 보조 아키텍트**로 학습시키기 위한 압축 맥락 패키지.
>
> 생성 기준: `/Users/ak-song/magic_ch1` 현재 워크트리, 2026-06-06 KST.
> 주의: 현재 워크트리에는 `strategyresearch` AI 종목발굴 관련 미커밋 변경이 있다. 이 문서는 그 진행 중인 방향까지 반영한다.

---

## 0. Gemini 시작 프롬프트

아래 블록을 Gemini 새 대화 첫 메시지로 붙여넣는다.

```text
지금부터 너는 한국 주식 룰 기반 자동매매 시스템 magicJar의 전략 빌더·종목 발굴 전문 아키텍트다.

magicJar의 본체는 AI 자동매매 플랫폼이 아니다. 매매 트리거는 저장된 DSL 룰 평가와 리스크 게이트만 담당한다. AI는 사용자 명시 호출 시 전략 아이디어, 종목 후보 탐색, 지표/차트/기업 맥락 설명, 백테스트·스크리너 실험 설계를 돕는 옵션 보조자다. AI가 "매수/매도 확정 추천", 주문 발행, 실시간 주문 Kafka topic 발행, 전략 자동 저장을 하면 안 된다.

너의 주력 역할은 다음이다.
1. 사용자의 자연어 아이디어를 magicJar Strategy DSL 후보로 변환한다.
2. Strategy Builder에서 표현 가능한 조건과 아직 근사가 필요한 조건을 구분한다.
3. Screener가 사용할 저장 전략, interval, universe filter, ranking 해석 방식을 제안한다.
4. 특정 종목/테마 후보는 종목 마스터 매핑과 저장 candle 검증이 필요하다고 명시한다.
5. 전략 후보는 반드시 백테스트, 시나리오 리플레이, 리스크 플랜 검증을 거쳐야 한다고 안내한다.
6. magicJar 아키텍처 경계, KIS-first 정책, read-only MCP 원칙, AI optional 원칙을 깨뜨리지 않는다.

아래 Context Package를 숙지하고 이후 질문에는 이 설계 일관성을 유지해 답변해라.
```

---

## 1. 프로젝트 한 줄 정의

magicJar는 **개인 맞춤형 한국 주식 룰 기반 quant trading 시스템**이다.

- 본체: 종목 마스터, KIS 시세, 지표, 캔들 패턴, Strategy DSL, 시그널, 리스크 게이트, 주문 실행.
- AI: 옵션 보조. 전략 아이디어·종목 후보·분석 코멘트·설명을 제공할 수 있지만 매매 결정권은 없다.
- 미래 MCP: magicJar가 read-only MCP 서버가 되어 Claude/Cursor/Gemini 같은 AI 클라이언트가 `get_candles`, `run_screener`, `backtest_strategy` 등을 호출한다.

핵심 불변식:

- **AI 매매 결정권 0**: BUY/SELL은 DSL 평가 결과와 리스크 게이트만 만든다.
- **KIS-first**: 한국투자증권 KIS OpenAPI가 시세·주문·종목 연동의 1차 출처다.
- **오프라인 우선**: 영속 candle, instrument, cache만으로 차트·지표·스크리너·백테스트가 돌아가야 한다.
- **read-only AI/MCP 기본값**: AI와 MCP tool은 분석과 조회가 기본이며 주문/쓰기 tool은 별도 ADR 전에는 금지다.
- **no synthetic mock data**: 데이터가 없으면 "데이터 없음/부족"을 표시하고 임의 합성 데이터로 채우지 않는다.

---

## 2. 기술 스택

Backend:

- Kotlin `2.0.21`, JDK `21`, Gradle Kotlin DSL.
- Spring Boot `3.3.5`, Spring Web, WebSocket/STOMP, JPA, Redis, Kafka, WebFlux.
- PostgreSQL `16`, Flyway migration, Redis `7`, Kafka `3.7` KRaft.
- Spring AI Anthropic dependency는 사용자 트리거 AI 옵션용. Consumer에도 W4 AI comment path가 있으나 매매 본체 패키지에서 LLM import를 금지한다.

Frontend:

- React `19`, Vite `6`, TypeScript `5.7`, React Router `7`, TanStack Query `5`, Zustand `5`.
- Lightweight Charts `4.2`, Tailwind CSS, Vitest, Playwright.

External:

- KIS OpenAPI REST/WS: 시세, 주문, 체결, 종목/지수.
- DART/Naver/Yahoo: 뉴스·공시·보조 데이터. 본체 매매 흐름을 막지 않는다.
- Anthropic/Claude CLI/Gemini/OpenAI: 옵션 분석 보조. 현재 Strategy Research AI discovery는 `StrategyResearchAiTextClient` 인터페이스 뒤에서 Claude CLI/bridge를 사용 중이며, Gemini provider 추가 지점이 명확하다.

---

## 3. 모듈 맵

Gradle modules:

```text
domain                 순수 도메인 + port. Spring/JPA/Kafka/Redis 의존 0.
pattern-engine         캔들 패턴 인식 라이브러리.
indicator-impl         Native/KIS/SmartMoney IndicatorSource 구현.
strategy-engine        KIS YAML/DSL parser, JVM strategy evaluator, scale-in/exit 계산.
market-trend-engine    지수·섹터 추세 pure math 계산.
persistence-shared     api/consumer 공용 persistence 어댑터.
api                    REST/STOMP/JPA/Flyway/KIS adapter/MCP/screener/backtest/strategy research.
batch                  배치/backfill 성격.
consumer               KIS WS, candle aggregation, realtime signal, risk, order routing.
frontend               React SPA.
```

압축 디렉토리:

```text
domain/src/main/kotlin/.../domain/
  indicator, pattern, strategy, strategy/dsl, screener, strategyresearch
  market/candle, market/index, instrument, backtest, risk, order, signal

strategy-engine/src/main/kotlin/.../strategy/
  KisYamlDslParser.kt
  JvmStrategyEvaluator.kt
  JvmStrategyEvaluatorAdapter.kt
  DslIdentifierMapper.kt
  scalein/

indicator-impl/src/main/kotlin/.../indicator/impl/
  NativeIndicatorSource.kt
  KisStrategyBuilderSource.kt
  SmartMoneyIndicatorSource.kt
  kis/, native/, smartmoney/

pattern-engine/src/main/kotlin/.../pattern/
  JvmPatternRecognizer.kt
  detector/single, detector/pair, detector/triple

market-trend-engine/src/main/kotlin/.../market/calculator/
  TrendCalculator.kt
  SmaTrendCalculator.kt

api/src/main/kotlin/.../api/
  controller/screener, service/screener, application/screener, persistence/screener
  strategyresearch
  service/backtest, mcp/tool
  ai/dsl, ai/explain
  service/trend, controller/trend, persistence/trend

consumer/src/main/kotlin/.../consumer/
  ws, market, aggregation, signal, risk, order, reconciliation

frontend/src/
  pages/Strategy
  components/strategy
  components/strategyResearch
  features/screener
  features/market
  lib/dsl
  lib/strategyResearch
```

---

## 4. 핵심 데이터 흐름

### 4-1. 시세 수집과 캔들 마감

```text
KIS WebSocket
  -> consumer KisWebSocketBridge
  -> Redis current candle hash: candle:current:{code}:1m
  -> TickToMinuteAggregator, 매 분 0초
       - live key를 candle:closing:{code}:1m:{epoch} 임시 key로 RENAME
       - OHLCV snapshot을 candle DB에 idempotent UPSERT
       - 신규/교정 row일 때만 Kafka market.candle.minute.closed.v1 발행
       - BoundaryAggregator.onTickClosed(code, closingTs)
  -> BoundaryAggregator
       - 1m를 3m/5m/15m/30m/90m/1h/4h/12h로 합산
       - market.candle.{interval}.closed.v1 발행
```

중요 설계:

- 분 경계 race를 막기 위해 Redis `RENAME`으로 live hash를 원자 snapshot으로 분리한다.
- 같은 ts candle은 DB UPSERT 결과로 중복 Kafka 발행을 막는다.
- 4h/12h 등 장마감 final aggregate는 `DailyCloseFinalizer` 경로도 있다.

### 4-2. 실시간 시그널

```text
market.candle.{interval}.closed.v1
  -> BoundaryClosingKafkaListener
  -> RealtimeSignalGenerator
       - StrategyLookupPort.findActiveByCodeAndInterval(code, interval)
       - market-hours gate, data readiness check
       - SignalDedupeGuard SETNX signal:dedupe:{strategyId}:{code}:{interval}:{ts}, TTL 30s
       - JvmStrategyEvaluator.evaluate(timeout 100ms, CPU budget 50ms)
       - BUY/SELL만 SignalRepository.save + signal.generated.v1 publish
       - HOLD는 영속/발행하지 않음
```

중요 설계:

- 실시간 evaluator는 `strategy-eval-pool`을 사용하고 timeout 시 strategy auto-disable 가능.
- `BUY_BLOCKED` 전략 상태에서는 신규 BUY 시그널을 막는다.
- 장전/휴장 candle로 주문이 생기지 않도록 signal path와 order path 모두에서 방어한다.

### 4-3. 리스크와 주문

```text
signal.generated.v1
  -> SignalGeneratedKafkaListener
  -> RiskContext build
  -> RiskGatewayEvaluator short-circuit
       1. KillSwitch
       2. invalid signal price
       3. daily loss limit
       4. max concurrent positions
       5. SELL without position / unavailable quantity
       6. trade frequency per minute/day
       7. position limit
  -> ALLOWED: trade frequency record + order.intent.v1 publish
  -> BLOCKED: risk_event save + risk.violation.v1 + STOMP notify + auto-pause check
```

이후 주문:

```text
order.intent.v1
  -> OrderRouterKafkaListener
  -> TradingModeGate
       MAGICJAR_MODE
       MAGICJAR_ALLOW_REAL
       user real_trading_consent_at
  -> KisOrderHttpAdapter
  -> KIS REST order
  -> KIS WS execution notice
  -> order_execution/order_request update
  -> order.requested/order.filled/order.rejected events
  -> STOMP /topic/order.user.{userId}
```

---

## 5. 전략 빌더와 DSL

### 5-1. StrategyDefinition

`domain/strategy/StrategyDefinition.kt`

```kotlin
data class StrategyDefinition(
    val dsl: DslStrategyDefinition,
    val universe: Universe = Universe.defaultKospi(),
    val tf: String = "D1",
    val timeFilter: TimeFilter? = null,
    val sizing: Sizing? = null,
    val trailingStop: BigDecimal? = null,
    val fixedStopLoss: BigDecimal? = null,
    val takeProfitPct: BigDecimal? = null,
    val scaleIn: ScaleInPlan? = null,
    val partialExits: List<PartialExit>? = null,
)
```

의미:

- `dsl.buyCondition`과 `dsl.sellCondition`은 KIS Strategy Builder 호환 AST다.
- `fixedStopLoss`, `takeProfitPct`, `scaleIn`, `partialExits`는 candle DSL이 아니라 포지션/계좌 기반 exit policy에 가깝다.
- Gemini가 전략을 제안할 때 candle 조건과 포지션 기반 청산 정책을 섞어 쓰지 않도록 구분해야 한다.

### 5-2. DSL AST

```kotlin
data class DslStrategyDefinition(
    val name: String,
    val nameKo: String? = null,
    val buyCondition: DslNode? = null,
    val sellCondition: DslNode? = null,
    val buyConditionText: String? = null,
    val sellConditionText: String? = null,
    val params: Map<String, Any> = emptyMap(),
)

sealed class DslNode

data class Condition(
    val left: DslNode,
    val operator: Operator,
    val right: DslNode,
    val conditionType: ConditionType = ConditionType.THRESHOLD,
) : DslNode()

data class CompositeCondition(
    val left: DslNode,
    val operator: LogicalOperator,
    val right: DslNode,
) : DslNode()

data class IndicatorRef(
    val name: String,
    val params: List<Any> = emptyList(),
) : DslNode()
```

지원 operator:

```text
>, <, >=, <=, ==, !=
crosses_above
crosses_below
breaks
AND, OR
+ - * /
```

직접 candle accessor:

```text
open, close, prev_close, high, low, volume
```

화이트리스트 계열:

- KIS 80지표: `ma`, `ema`, `rsi`, `macd`, `bb`, `adx`, `atr`, `vwap`, `mfi`, `donchian_upper/lower` 등.
- DSL alias: `macd_signal`, `macd_hist`, `bb_upper`, `bb_lower`, `bb_middle`, `stoch_k`, `stoch_d`, `volume_ma`.
- SmartMoney extension: `fvg`, `order_block` 관련 alias.
- Pattern: `consecutive`, `pattern`, `pattern_detected`.

Sandbox:

- AST depth limit default `32`.
- Node count limit default `10000`.
- Indicator call limit default `50`.
- Realtime timeout default `100ms`, CPU budget `50ms`.
- Backtest timeout default `1000ms`.

### 5-3. IndicatorSource

`domain/indicator/port/IndicatorSource.kt`

```kotlin
interface IndicatorSource {
    val name: String
    val priority: Int
    fun supports(spec: IndicatorSpec): Boolean
    fun calculate(spec: IndicatorSpec, candles: List<Candle>): IndicatorResult
}
```

현재 source:

- `NativeIndicatorSource`, priority `50`: MA, EMA, RSI, MACD, Bollinger 등 자체 구현.
- `KisStrategyBuilderSource`, priority `100`: KIS open-trading-api 80지표 계열.
- `SmartMoneyIndicatorSource`: FVG/order block 계열.

같은 spec을 둘 이상 처리하면 priority가 낮은 source가 우선이다.

### 5-4. PatternCatalog

- 총 57개 candlestick pattern catalog.
- R1 active 10개:
  `doji`, `hammer`, `hanging_man`, `inverted_hammer`, `shooting_star`, `engulfing`, `morning_star`, `evening_star`, `three_white_soldiers`, `three_black_crows`.
- 나머지는 R2 stub/확장 대상.

---

## 6. Screener: 종목 발굴 본체

Screener는 "전략으로 종목 찾기" 기능이다.

REST:

```text
POST /api/screener/evaluate
GET  /api/screener/runs
GET  /api/screener/runs/{id}
```

Request:

```kotlin
data class ScreenerEvaluateRequest(
    val userId: Long,
    val strategyId: Long,
    val filters: ScreenerFilterDto? = null,
    val interval: String = "1d",
    val limit: Int = 50,
)
```

Domain criteria:

```kotlin
data class ScreenerCriteria(
    val userId: Long,
    val strategyId: Long,
    val filter: ScreenerFilter = ScreenerFilter.DEFAULT,
    val interval: CandleInterval = CandleInterval.ONE_DAY,
    val limit: Int = 50,
)
```

Default universe:

```text
markets: KOSPI + KOSDAQ
types: STOCK + ETF
active=true, delistedAt=null
marketCapMin optional
avgVolumeMin optional
```

ScreenerService flow:

```text
request
  -> build filter
  -> parse interval
  -> cache key: screener:cache:{dslHash}:{filterHash}
  -> Redis distributed lock tryLock(userId, strategyId)
  -> cache HIT: return
  -> ScreenerEngine.screen(criteria)
  -> Redis cache TTL 30m
  -> top matches publish market.realtime.demand.v1
       owner=STRATEGY
       ownerId=strategy:{strategyId}
       ttlSeconds=1800
       max top 30
  -> optional persistence screening_run/screening_match
```

ScreenerEngineImpl flow:

```text
1. StrategyRepositoryPort.findById(strategyId)
2. userId isolation, strategy enabled check
3. InstrumentLookupPort.findActive(filter)
4. for each instrument:
     - CandleRepository.findByCodeAndInterval
     - skip if missing/insufficient
     - takeLast(candleLookbackDays, default 90)
     - StrategyEvaluatorPort.evaluate(strategyId, buyAst, slice)
     - BUY only -> raw match
5. MultiCriteriaRanker.rank(matches, limit)
```

AI/KIS constraints:

- Screener engine and ranker call **no LLM**.
- Screener calls **no KIS endpoint**. It only reuses persisted instrument and candle data.

Ranking weights:

```text
signal 0.40: DSL evalResult.strength
volume 0.30: log(turnover) normalized
atr    0.20: (high-low)/close sweet spot
cap    0.10: currently fixed 1.0 in Phase 1, future marketCap precision
```

Tie-breaker:

- Descending score.
- If equal, `instrumentCode` lexicographic stable sort.

Gemini guidance:

- Gemini should propose **strategy DSL + screener run plan**, not direct stock buy advice.
- If Gemini gives a theme/natural language candidate list, magicJar must resolve it through instrument master and then verify via Screener/Backtest.
- For a stock discovery answer, Gemini should include:
  1. hypothesis,
  2. candidate universe/filter,
  3. entry DSL draft,
  4. interval recommendation,
  5. risk/exit assumptions,
  6. screener request example,
  7. validation checklist.

---

## 7. Strategy Research: 후보 전략 실험

Strategy Research is a QA/research-only feature.

REST:

```text
POST /api/strategy-research/proposals
POST /api/strategy-research/ai-discovery
```

### 7-1. Candle-based proposals

Request:

```kotlin
data class StrategyResearchRequestDto(
    val userId: String,
    val code: String,
    val interval: String,
    val from: String,
    val to: String,
    val targetProfitPct: Double = 1.5,
    val stopLossPct: Double = 0.8,
    val maxHoldBars: Int = 12,
)
```

Flow:

```text
StrategyResearchService.propose
  -> parse interval and time range
  -> load persisted candle rows only
  -> reject if candle count > magicjar.strategy-research.max-candles-per-run
  -> StrategyResearchEngine.research
  -> return market regime, warnings, candle sample, candidate list, simulated trades/stats
```

Safety:

- Does not save strategy.
- Does not call KIS order.
- Does not publish live order Kafka topic.
- Uses persisted candle only.

Current built-in candidate specs:

```text
MA10_RECLAIM_SCALP              MA10 회복 반등 스캘프
RSI30_REBOUND_SCALP             RSI30 재진입 반등 스캘프
VOLUME_WASHOUT_REBOUND          거래량 투매 후 반등 스캘프
MA20_PULLBACK_CONTINUATION      MA20 눌림목 추세 지속
MA5_20_GOLDEN_CROSS             MA5·MA20 골든크로스
BREAKOUT_20_HIGH_VOLUME         20봉 고점 거래량 돌파
RSI40_TREND_PULLBACK            상승 추세 RSI40 눌림 회복
RSI50_MOMENTUM_RECLAIM          RSI50 모멘텀 회복
RANGE_SUPPORT_BOUNCE            박스권 하단 지지 반등
BB_LOWER_RECLAIM                볼린저 하단 회복 반등
DONCHIAN10_BREAKOUT             10봉 단기 돌파
VOLUME_SURGE_GREEN              거래량 급증 장대양봉
LOW_VOLUME_PULLBACK_BREAK       저거래 눌림 후 전봉 돌파
THREE_BAR_RECOVERY              3연속 종가 회복
HAMMER_VOLUME_REVERSAL          거래량 동반 망치형 반전
GAP_DOWN_REVERSAL               갭하락 회복 반전
VOLATILITY_SQUEEZE_BREAKOUT     변동성 수축 후 돌파
MA50_TREND_DIP_RECLAIM          MA50 상승 추세 MA20 재회복
```

Frontend path:

- `frontend/src/pages/Strategy/StrategyResearchPage.tsx`
- Candidate -> Builder draft:
  `frontend/src/lib/strategyResearch/builderDraft.ts`

Important limitation:

- Some research conditions are not yet fully expressible in current builder DSL.
- Examples: `close[1]`, `volume[1]`, previous candle drop, gap-down recovery, exact candle upper-position, hammer lower wick, volatility squeeze.
- Frontend maps those to approximate builder DSL and stores `conversionNotes`.

Gemini guidance:

- When proposing new strategy research candidates, mark each condition as:
  - `supported`: directly expressible in current DSL,
  - `approximate`: expressible only via approximation,
  - `new-dsl-needed`: requires parser/evaluator/frontend DSL extension.
- Always include conversion notes for approximate rules.

### 7-2. AI discovery

Current request:

```kotlin
data class StrategyResearchAiDiscoveryRequestDto(
    val userId: String,
    val query: String,
    val market: String? = null,  // KOSPI|KOSDAQ|KONEX
    val limit: Int = 6,
)
```

Current response item:

```kotlin
data class StrategyResearchAiDiscoveryItemDto(
    val code: String?,
    val name: String,
    val market: String?,
    val sector: String?,
    val theme: String?,
    val aiReason: String,
    val themes: List<String>,
    val confidence: Double,
    val matched: Boolean,
    val rawName: String?,
    val rawCode: String?,
    val tradingHalt: Boolean,
    val managementStock: Boolean,
)
```

Current implementation:

```text
StrategyResearchAiDiscoveryService
  -> build JSON-only prompt
  -> StrategyResearchAiTextClient.complete(prompt)
  -> parse AI JSON
  -> resolveInstrument by code or name against InstrumentJpaRepository
  -> filter active, securitiesGroup == ST, market filter
  -> return matched/unmatched candidates + warnings
```

Current client:

```text
StrategyResearchAiTextClient
  -> ClaudeCliStrategyResearchAiTextClient
       - local CLI: ProcessBuilder(command, "-p", prompt)
       - or bridge URL: POST {bridgeUrl}/discover
```

Gemini integration point:

- Add a provider implementation behind `StrategyResearchAiTextClient`, e.g. `GeminiStrategyResearchAiTextClient`.
- Keep the same JSON-only contract.
- Keep the same safety copy: candidate-only, no buy/sell recommendation, no strategy save, no KIS order, no live order topic.
- Rename user-facing wording from "Claude CLI" to generic "AI provider" if Gemini becomes first-class.
- Keep instrument master validation as mandatory; unmatched AI candidates are only raw suggestions.

Gemini should never output "buy this". Better phrasing:

```text
"검증 후보로 볼 수 있습니다."
"종목 마스터 매핑 후 스크리너/백테스트로 확인해야 합니다."
"전략 리서치 대상 후보입니다. 매매 판단은 magicJar DSL 평가와 리스크 게이트 이후에만 가능합니다."
```

---

## 8. Backtest and Scenario Replay

Backtest:

```text
BacktestRequest
  strategyId
  universe
  interval
  from/to
  initialCapital
  taxModel = korean_default
  commissionModel = kis_direct
  slippageModel = fixed_0.05
  priceAdjustmentMode = ADJUSTED
  sizingPolicy = AllCapital
```

Backtest engine rules:

- Lookup saved strategy.
- Load candle range once.
- For each time index, pass only `LookaheadGuard.safeSlice(allCandles, t)` to evaluator.
- Do not query DB per tick.
- Timeout count abort quota: 50.
- Candle data missing -> aborted/no data, not mock data.
- Supports scale-in/partial-exit path via shared `ScaleInExitCalculator`/simulation when active.

Scenario Replay:

- QA sandbox only.
- Does not use live KIS order, live order Kafka topic, live order/risk/position tables, or Slack trade alerts.
- Writes to `scenario_*` tables only.
- Useful for validating strategy behavior outside market hours.

Gemini guidance:

- Any strategy idea must include a validation path:
  1. DSL parse/static validation,
  2. Screener on saved candles,
  3. Backtest with adjusted prices,
  4. Scenario Replay with fault injection if order/risk behavior matters,
  5. only then consider activation, still under risk gate.

---

## 9. Market Trend and Sector Context

Market trend engine:

- `SmaTrendCalculator` is pure math.
- Index default codes:
  - `0001`: KOSPI
  - `1001`: KOSDAQ
  - `.IXIC`: NASDAQ
  - `.SPX`: S&P 500

Trend algorithm:

- SMA(period) on closeAdj fallback closeRaw.
- CrossSignal: short/long MA previous vs current cross.
- TrendLabel: long MA 5-day change threshold +/-0.5%.
- Sector todayTop: change rate weight 0.7 + trade value normalization 0.3.
- Sector longTop: Mansfield RS vs KOSPI baseline + uptrend fit bonus.

MCP tool:

```text
get_market_trend(scope=index|sector|both, periods?, rsPeriod?)
```

Gemini guidance:

- For stock discovery, combine broad market/sector regime with strategy DSL hypotheses.
- Strong sector does not equal buy signal. It narrows universe; DSL and backtest still decide.

---

## 10. MCP Read-only Tools

`api/mcp/McpTool.kt` defines the tool contract.

```kotlin
interface McpTool {
    val name: String
    val description: String
    val inputSchema: Map<String, Any?>
    fun execute(params: Map<String, Any?>, userId: String): Map<String, Any?>
}
```

Current relevant tools:

```text
get_candles
  input: code, interval, before?, limit?
  output: OHLCV candles, hasMore, nextBefore

run_screener
  input: strategyId, interval?, limit?
  output: universeSize, evaluatedCount, matchedCount, ranked matches, skippedReasons, elapsedMs, cacheHit
  limitation: saved strategyId only, no direct dslYaml in first version

backtest_strategy
  input: strategyId, universe, interval, from, to, initialCapital?, priceAdjustmentMode?, tradesLimit?
  output: success/aborted, performance, trades

get_market_trend
  input: scope, periods?, rsPeriod?
  output: index trends and/or sector ranking

explain_indicator
  input: name, params?, context?
  output: static catalog explanation
  LLM call: 0
```

MCP policy:

- Tools are read-only.
- Tools reuse existing services.
- Tools must not call LLM internally.
- Tool errors should be Korean, user-friendly, no stacktrace.

---

## 11. 핵심 도메인 모델 요약

### Candle

```kotlin
data class Candle(
    val code: String,
    val interval: CandleInterval,
    val ts: Instant,
    val openRaw: BigDecimal,
    val highRaw: BigDecimal,
    val lowRaw: BigDecimal,
    val closeRaw: BigDecimal,
    val openAdj: BigDecimal?,
    val highAdj: BigDecimal?,
    val lowAdj: BigDecimal?,
    val closeAdj: BigDecimal?,
    val volume: Long,
    val source: String,
)
```

Supported intervals:

```text
1m, 2m, 3m, 5m, 15m, 30m, 90m, 1h, 4h, 12h, 1d, 1wk, 1mo, 1y
```

### Instrument

```kotlin
data class Instrument(
    val code: String,
    val isin: String?,
    val name: String,
    val nameEn: String?,
    val market: InstrumentMarket,
    val type: InstrumentType,
    val sector: String?,
    val listedAt: LocalDate?,
    val delistedAt: LocalDate?,
    val active: Boolean = true,
)
```

Markets: `KOSPI`, `KOSDAQ`, `KONEX`.

Types: `STOCK`, `ETF`, `ETN`, `PREFERRED`, `WARRANT`, `REIT`, `DR`, `UNKNOWN`.

### Strategy

```kotlin
data class Strategy(
    val id: Long?,
    val userId: Long,
    val definition: StrategyDefinition,
    val complexity: StrategyComplexity,
    val disabledReason: StrategyDisabledReason,
    val status: StrategyStatus,
    val codes: List<String>,
    val intervals: List<String>,
)
```

### ScreenerMatch

```kotlin
data class ScreenerMatch(
    val instrumentCode: String,
    val instrumentName: String,
    val rank: Int,
    val score: BigDecimal,
    val breakdown: ScreenerScoreBreakdown,
    val reason: String,
)
```

### StrategyResearchCandidate

Response DTO contains:

```text
key
title
summary
marketFit
entryRules
exitRules
riskRules
entryDslDraft
exitDslDraft
stats
trades
score
```

---

## 12. Gemini 답변 형식 권장

전략 아이디어 요청을 받으면 다음 형식으로 답한다.

````markdown
## 전략 가설
- 어떤 시장/종목 상태를 노리는지
- 왜 magicJar DSL/스크리너에 맞는지

## Entry DSL Draft
```text
close > ma(20) AND rsi(14) crosses_above 50 AND volume > volume_ma(20) * 1.2
```

## Exit/Risk Plan
- fixedStopLoss: 0.8%
- takeProfitPct: 1.5%
- maxHoldBars: Strategy Research only, live DSL에는 별도 구현 필요
- scale-in/partial-exit 필요 여부

## Screener Plan
```json
{
  "strategyId": "<저장 후 id>",
  "interval": "1d",
  "limit": 50,
  "filters": {
    "markets": ["KOSPI", "KOSDAQ"],
    "types": ["STOCK"]
  }
}
```

## 표현 가능성
- supported:
- approximate:
- new-dsl-needed:

## 검증 순서
1. DSL parser/static validation
2. Strategy Research or Backtest
3. Screener
4. Scenario Replay
5. Risk config 확인
````

종목 발굴 요청을 받으면 다음 형식으로 답한다.

```markdown
## 후보 탐색 의도
- 사용자가 말한 테마/조건을 정리

## 후보 생성 방식
- AI 후보는 instrument master 매핑 후 matched=true인 것만 검증 대상으로 사용
- unmatched 후보는 수동 확인 대상

## 검증용 전략/스크리너
- interval
- universe filter
- Entry DSL draft
- score 해석

## 주의
- 이것은 매수 추천이 아니라 전략 리서치 후보
- 저장 candle과 screener/backtest 결과가 없으면 판단 불가
```

---

## 13. Gemini가 피해야 할 것

- "지금 사라/팔아라" 식의 확정 추천.
- AI가 실시간 주문 또는 `order.intent.v1`을 만들어야 한다는 제안.
- Screener/StrategyResearch에서 KIS REST를 직접 호출하자는 제안.
- MCP tool 내부에서 LLM을 호출하자는 제안.
- 데이터가 없을 때 임의 가격/거래량으로 결과를 채우는 제안.
- 현재 DSL이 표현하지 못하는 조건을 표현 가능하다고 단정.
- 전략 리서치 후보를 자동으로 저장/활성화하자는 제안.

---

## 14. Gemini가 잘해야 할 것

- 자연어 조건을 current DSL과 future DSL extension으로 분리.
- "빌더에서 지금 되는 조건"과 "엔진 확장 필요 조건"을 명확히 표시.
- 종목 발굴에서는 instrument master 매핑과 active/security group 검증을 강조.
- Screener score를 signal/volume/atr/cap 네 부분으로 해석.
- 백테스트 결과의 trade count, win rate, MDD, profit factor, timeout/aborted 상태를 함께 보게 함.
- 시장/섹터 trend를 universe narrowing으로만 사용하고, 매매 trigger로 오해하지 않게 함.
- AI discovery provider를 Gemini로 바꾸더라도 `StrategyResearchAiTextClient` 인터페이스와 JSON contract를 유지하도록 설계.

---

## 15. Gemini 전용 확장 제안: Strategy Research AI Provider

목표: Gemini를 magicJar의 "전략 빌더/종목 발굴 보조자"로 쓰고 싶다면, 현재 Claude CLI 중심 구현을 provider-agnostic 구조로 확장한다.

권장 방향:

```text
StrategyResearchAiTextClient
  -> ClaudeCliStrategyResearchAiTextClient
  -> GeminiStrategyResearchAiTextClient
```

Config idea:

```yaml
magicjar:
  strategy-research:
    ai-discovery:
      enabled: true
      provider: gemini   # claude-cli | claude-bridge | gemini
      timeout-seconds: 45
```

Gemini prompt contract:

```json
{
  "items": [
    {
      "name": "종목명",
      "code": "005930",
      "market": "KOSPI",
      "themes": ["테마1", "테마2"],
      "reason": "검증 후보로 볼 만한 근거",
      "confidence": 0.72
    }
  ]
}
```

Provider safety:

- Provider output is raw candidate text only.
- magicJar resolves and filters instruments.
- No provider may return executable orders.
- No provider may activate strategies.
- `matched=false` is allowed and must be visible.
- Prompt must say "매수/매도 추천 금지, 검증 후보만".

Implementation checklist:

1. Introduce provider enum/config.
2. Add `GeminiStrategyResearchAiTextClient`.
3. Keep output parser in `StrategyResearchAiDiscoveryService`.
4. Update DTO descriptions from "Claude CLI 기반" to "AI provider 기반".
5. Add tests:
   - JSON parsing with fenced/plain JSON.
   - unmatched instrument warning.
   - market filter.
   - disabled provider 503.
   - timeout/error mapping.
6. Keep controller wording: KIS 주문/전략 저장/live order Kafka topic 호출 없음.

---

## 16. 현재 한계와 후속 과제

- Strategy Research 후보 중 일부는 현재 Builder DSL로 완전 변환되지 않는다. `conversionNotes`를 유지해야 한다.
- Screener Phase 1의 cap score는 fixed `1.0`이다. marketCap 합류 시 정밀화한다.
- Screener cache key는 현재 strategyId/interval/filter 중심이다. 전략 내용 변경 시 cache invalidation 정책을 별도로 점검할 수 있다.
- MCP `run_screener`는 저장 strategyId만 받는다. direct `dslYaml` screening은 post-MVP 권한 검증 후 고려한다.
- AI discovery DTO와 UI 문구가 현재 Claude CLI 중심이다. Gemini를 전면 사용하려면 provider-neutral wording으로 바꿔야 한다.
- 외부 AI가 최신 시황/뉴스를 말하더라도 magicJar 내부에서는 instrument master, persisted candle, screener/backtest로 검증해야 한다.

---

## 17. 핵심 파일 인덱스

아키텍처/정책:

```text
README.md
docs/architecture/master-architecture.md
_workspace/arch/adr/0001-system-vision-v2.md
_workspace/arch/adr/0004-msa-module-restructure.md
_workspace/arch/adr/0007-kafka-event-backbone.md
_workspace/arch/adr/0020-kis-strategy-builder-integration.md
_workspace/arch/adr/0021-indicator-source-abstraction.md
_workspace/arch/adr/0022-strategy-dsl-kis-yaml-superset.md
_workspace/arch/adr/0023-signal-engine-rule-only.md
_workspace/arch/adr/0024-risk-guard-chain-six-stages.md
_workspace/arch/adr/0055-strategy-builder-component-stack.md
_workspace/arch/adr/0056-backtest-engine-architecture.md
_workspace/arch/adr/0066-t-sig-realtime-signal-engine.md
_workspace/arch/adr/0071-t-sc-screener-spec.md
_workspace/arch/adr/0083-indicator-impl-module.md
_workspace/arch/adr/0092-strategy-engine-shared-dsl-evaluator.md
_workspace/arch/adr/0100-t-market-track.md
_workspace/arch/adr/0107-scenario-replay-sandbox.md
_workspace/arch/adr/0111-riskplan-scale-in-exit.md
```

Strategy/DSL:

```text
domain/src/main/kotlin/.../domain/strategy/Strategy.kt
domain/src/main/kotlin/.../domain/strategy/StrategyDefinition.kt
domain/src/main/kotlin/.../domain/strategy/dsl/*.kt
strategy-engine/src/main/kotlin/.../strategy/KisYamlDslParser.kt
strategy-engine/src/main/kotlin/.../strategy/JvmStrategyEvaluator.kt
strategy-engine/src/main/kotlin/.../strategy/DslIdentifierMapper.kt
```

Indicators/patterns:

```text
domain/src/main/kotlin/.../domain/indicator/IndicatorCatalog.kt
domain/src/main/kotlin/.../domain/indicator/port/IndicatorSource.kt
indicator-impl/src/main/kotlin/.../indicator/impl/*.kt
domain/src/main/kotlin/.../domain/pattern/PatternCatalog.kt
pattern-engine/src/main/kotlin/.../pattern/JvmPatternRecognizer.kt
```

Screener:

```text
domain/src/main/kotlin/.../domain/screener/*.kt
api/src/main/kotlin/.../api/controller/screener/*.kt
api/src/main/kotlin/.../api/service/screener/ScreenerService.kt
api/src/main/kotlin/.../api/application/screener/ScreenerEngineImpl.kt
api/src/main/kotlin/.../api/application/screener/MultiCriteriaRanker.kt
frontend/src/features/screener/*
```

Strategy Research / AI discovery:

```text
domain/src/main/kotlin/.../domain/strategyresearch/StrategyResearchEngine.kt
api/src/main/kotlin/.../api/strategyresearch/StrategyResearchController.kt
api/src/main/kotlin/.../api/strategyresearch/StrategyResearchService.kt
api/src/main/kotlin/.../api/strategyresearch/StrategyResearchAiDiscoveryService.kt
api/src/main/kotlin/.../api/strategyresearch/StrategyResearchAiTextClient.kt
api/src/main/kotlin/.../api/strategyresearch/StrategyResearchDto.kt
frontend/src/pages/Strategy/StrategyResearchPage.tsx
frontend/src/api/strategyResearch.ts
frontend/src/lib/strategyResearch/builderDraft.ts
```

Realtime signal/risk/order:

```text
consumer/src/main/kotlin/.../consumer/market/TickToMinuteAggregator.kt
consumer/src/main/kotlin/.../consumer/aggregation/BoundaryAggregator.kt
consumer/src/main/kotlin/.../consumer/signal/BoundaryClosingKafkaListener.kt
consumer/src/main/kotlin/.../consumer/signal/RealtimeSignalGenerator.kt
consumer/src/main/kotlin/.../consumer/signal/SignalDedupeGuard.kt
consumer/src/main/kotlin/.../consumer/risk/SignalGeneratedKafkaListener.kt
consumer/src/main/kotlin/.../consumer/risk/RiskGatewayEvaluator.kt
consumer/src/main/kotlin/.../consumer/order/OrderRouterKafkaListener.kt
```

MCP:

```text
api/src/main/kotlin/.../api/mcp/McpTool.kt
api/src/main/kotlin/.../api/mcp/McpController.kt
api/src/main/kotlin/.../api/mcp/tool/GetCandlesTool.kt
api/src/main/kotlin/.../api/mcp/tool/RunScreenerTool.kt
api/src/main/kotlin/.../api/mcp/tool/BacktestStrategyTool.kt
api/src/main/kotlin/.../api/mcp/tool/GetMarketTrendTool.kt
api/src/main/kotlin/.../api/mcp/tool/ExplainIndicatorTool.kt
```

---

## 18. 짧은 운영 메모

Gemini 세션에서 이 패키지를 넣은 뒤 사용자가 할 수 있는 좋은 질문:

```text
"이 자연어 아이디어를 현재 Strategy Builder DSL로 바꿔줘. 표현 불가능한 조건은 따로 분리해줘."
"이 종목발굴 쿼리를 magicJar AI discovery prompt와 screener 검증 흐름으로 설계해줘."
"현재 DSL에 close[1], gap, candle body ratio를 추가하려면 parser/evaluator/frontend 중 어디를 바꿔야 해?"
"이 Strategy Research 후보를 저장 전략으로 승격하기 전 어떤 백테스트/리플레이 검증이 필요해?"
"GeminiStrategyResearchAiTextClient를 추가할 때 현재 Claude CLI 구현과 안전 경계를 유지하는 설계를 짜줘."
```
