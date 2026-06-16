package kr.spdaewoon.magicjar.strategy

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kr.spdaewoon.magicjar.domain.indicator.IndicatorCatalog
import kr.spdaewoon.magicjar.domain.strategy.ComplexityClass
import kr.spdaewoon.magicjar.domain.strategy.StrategyComplexity
import kr.spdaewoon.magicjar.domain.strategy.StrategyException
import kr.spdaewoon.magicjar.domain.strategy.dsl.ArithmeticExpr
import kr.spdaewoon.magicjar.domain.strategy.dsl.ArithmeticOperator
import kr.spdaewoon.magicjar.domain.strategy.dsl.CompositeCondition
import kr.spdaewoon.magicjar.domain.strategy.dsl.Condition
import kr.spdaewoon.magicjar.domain.strategy.dsl.ConditionType
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslStrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.dsl.IndicatorRef
import kr.spdaewoon.magicjar.domain.strategy.dsl.LiteralValue
import kr.spdaewoon.magicjar.domain.strategy.dsl.LogicalOperator
import kr.spdaewoon.magicjar.domain.strategy.dsl.Operator
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * KIS Strategy Builder DSL parser.
 *
 * Source: parser.py:L301-L530 · License: MIT
 * Catalog: _workspace/specs/pr-35-kis-dsl-raw-verification.md §2 (자체 추론 0)
 *
 * ### Pipeline
 * 1. **tokenize** — 21 token patterns 정규식 (parser.py:L304-L322 LOCK)
 * 2. **parse** — 6단 재귀 하강 (parser.py:L412-L530 LOCK)
 *    - `_parse_or_expression` (우선순위 1, 최저)
 *    - `_parse_and_expression` (우선순위 2)
 *    - `_parse_condition` (우선순위 3, operator 매칭)
 *    - `_parse_arithmetic` (우선순위 4, `+` `-`)
 *    - `_parse_term` (우선순위 5, `*` `/`)
 *    - `_parse_factor` (우선순위 6, 단항 `-`, NUMBER, IDENTIFIER, `( )`, IndicatorRef)
 * 3. **validateStatic** — AST visitor (depth · 노드 · indicator · 화이트리스트, ADR 0047 §2-1)
 *
 * ### Sandbox 한도 (env 주입, ADR 0047 §2-7)
 * - `MAGICJAR_DSL_AST_DEPTH_LIMIT` default 32
 * - `MAGICJAR_DSL_NODE_COUNT_LIMIT` default 10000
 * - `MAGICJAR_DSL_INDICATOR_CALL_LIMIT` default 50
 *
 * ### 화이트리스트 (ADR 0047 §2-1-A)
 * - 80지표 모두 ([IndicatorCatalog.ALL])
 * - DSL alias 8종 (`macd_signal`·`macd_hist`·`bb_upper`·`bb_lower`·`bb_middle`·`stoch_k`·`stoch_d`·`volume_ma`)
 * - 패턴 함수 3종 (`consecutive`·`pattern`·`pattern_detected`) — T-PAT JvmPatternRecognizer 호출
 *   - `pattern_detected("name")` — magicJar 자체 alias (T-PAT PR-39 추가). KIS DSL `pattern("name")`과 동치 라우팅.
 * - 캔들 직접 접근 6종 (`open`·`close`·`prev_close`·`high`·`low`·`volume`)
 * - 자체 함수 4종 (`change`·`highest`·`lowest`·`returns`)
 *
 * Repository default fun 함정 — ADR 0051 §3 정합. 본 클래스는 stateless `@Component` (Spring bean).
 */
@Component
class KisYamlDslParser(
    @Value("\${magicjar.dsl.ast-depth-limit:32}") private val astDepthLimit: Int,
    @Value("\${magicjar.dsl.node-count-limit:10000}") private val nodeCountLimit: Int,
    @Value("\${magicjar.dsl.indicator-call-limit:50}") private val indicatorCallLimit: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        log.info(
            "Loaded DSL parser with {} token patterns (depth_limit={}, node_limit={}, indicator_limit={})",
            TOKEN_PATTERNS.size,
            astDepthLimit,
            nodeCountLimit,
            indicatorCallLimit,
        )
    }

    /**
     * 텍스트 DSL → AST.
     *
     * @throws StrategyException.DslSyntaxException tokenize/parse 실패 시
     */
    fun parse(dslText: String): DslNode {
        val tokens = tokenize(dslText)
        if (tokens.isEmpty()) {
            throw StrategyException.DslSyntaxException("Empty DSL expression")
        }
        val parser = ParseState(tokens)
        val root = parser.parseOrExpression()
        if (parser.hasMore()) {
            throw StrategyException.DslSyntaxException(
                "Unexpected token at position ${parser.position()}: ${parser.peek()?.type}=${parser.peek()?.value}",
            )
        }
        return root
    }

    /**
     * `.kis.yaml` 또는 magicJar superset YAML → [DslStrategyDefinition].
     *
     * Source: ADR 0022 §2-3 + spec §5-3 (KIS 호환) · §5-4 (magicJar export comment fallback) · §8-8 (jackson-dataformat-yaml).
     *
     * 핵심 필드:
     * - `name` / `name_ko`
     * - `buy_condition` (텍스트 1줄)
     * - `sell_condition` (텍스트 1줄)
     * - `params` (Map — initial_capital · position_size 등)
     *
     * magicJar 확장(`tf` · `time` · `sizing` · `trailingStop` 등)은 본 메서드에서 무시 — backend-core
     * StrategyService가 별도로 wrapper StrategyDefinition으로 흡수.
     */
    fun parseYaml(yamlContent: String): DslStrategyDefinition {
        val tree = YAML_MAPPER.readValue(yamlContent, Map::class.java)

        @Suppress("UNCHECKED_CAST")
        val map = tree as Map<String, Any?>
        val name =
            (map["name"] as? String)
                ?: throw StrategyException.DslSyntaxException("YAML missing required 'name' field")
        val nameKo = map["name_ko"] as? String
        val buyText = map["buy_condition"] as? String
        val sellText = map["sell_condition"] as? String
        val buy =
            buyText?.let {
                runCatching { parse(it) }.getOrElse { e ->
                    throw StrategyException.DslSyntaxException("buy_condition: ${e.message}")
                }
            }
        val sell =
            sellText?.let {
                runCatching { parse(it) }.getOrElse { e ->
                    throw StrategyException.DslSyntaxException("sell_condition: ${e.message}")
                }
            }

        @Suppress("UNCHECKED_CAST")
        val params = (map["params"] as? Map<String, Any>) ?: emptyMap()
        return DslStrategyDefinition(
            name = name,
            nameKo = nameKo,
            buyCondition = buy,
            sellCondition = sell,
            buyConditionText = buyText,
            sellConditionText = sellText,
            params = params,
        )
    }

    /**
     * Static 검증 (ADR 0047 §2-1) — depth · 노드 · indicator · 화이트리스트.
     *
     * @return [StrategyComplexity] (LOW/MEDIUM/HIGH/CRITICAL 4단)
     * @throws StrategyException.DslDepthExceededException · DslNodeCountExceededException · DslIndicatorCallExceededException · UnknownDslSymbolException
     */
    fun validateStatic(ast: DslNode): StrategyComplexity {
        val visitor = StaticVisitor()
        visitor.walk(ast, depth = 1)

        if (visitor.maxDepth > astDepthLimit) {
            throw StrategyException.DslDepthExceededException(visitor.maxDepth, astDepthLimit)
        }
        if (visitor.nodeCount > nodeCountLimit) {
            throw StrategyException.DslNodeCountExceededException(visitor.nodeCount, nodeCountLimit)
        }
        if (visitor.indicatorCallCount > indicatorCallLimit) {
            throw StrategyException.DslIndicatorCallExceededException(visitor.indicatorCallCount, indicatorCallLimit)
        }
        visitor.unknownSymbol?.let { throw StrategyException.UnknownDslSymbolException(it) }

        val estimatedMs = visitor.indicatorCallCount * 4
        val complexityClass =
            when {
                visitor.maxDepth <= 4 && visitor.nodeCount <= 20 && visitor.indicatorCallCount <= 5 -> ComplexityClass.LOW
                visitor.maxDepth <= 8 && visitor.nodeCount <= 100 && visitor.indicatorCallCount <= 15 -> ComplexityClass.MEDIUM
                visitor.maxDepth <= 16 && visitor.nodeCount <= 500 && visitor.indicatorCallCount <= 30 -> ComplexityClass.HIGH
                else -> ComplexityClass.CRITICAL
            }

        return StrategyComplexity(
            astDepth = visitor.maxDepth,
            nodeCount = visitor.nodeCount,
            indicatorCallCount = visitor.indicatorCallCount,
            estimatedEvalTimeMs = estimatedMs,
            complexityClass = complexityClass,
        )
    }

    // =====================================================================
    // Tokenize (parser.py:L304-L350)
    // =====================================================================

    /**
     * 단일 token (KIS Python tuple `(type, value, position)` 정합).
     */
    private data class Token(
        val type: String,
        val value: String,
        val position: Int,
    )

    /**
     * Tokenize — 21 token patterns 순차 매칭 (parser.py:L325-L350 LOCK).
     *
     * WHITESPACE는 스킵. 매칭 실패 시 [StrategyException.DslSyntaxException].
     */
    private fun tokenize(text: String): List<Token> {
        val out = mutableListOf<Token>()
        var pos = 0
        while (pos < text.length) {
            var matched = false
            for ((type, regex) in TOKEN_PATTERNS) {
                val match = regex.matchAt(text, pos) ?: continue
                if (type != "WHITESPACE") {
                    out.add(Token(type, match.value, pos))
                }
                pos += match.value.length
                matched = true
                break
            }
            if (!matched) {
                throw StrategyException.DslSyntaxException(
                    "Unrecognized token at position $pos: '${text.substring(pos, minOf(pos + 10, text.length))}'",
                )
            }
        }
        return out
    }

    // =====================================================================
    // Parse — 재귀 하강 (parser.py:L412-L530)
    // =====================================================================

    /**
     * 재귀 하강 파서 상태. Kotlin nested class — KIS Python self.tokens · self.pos 정합.
     */
    private inner class ParseState(
        private val tokens: List<Token>,
    ) {
        private var pos = 0

        fun position(): Int = pos

        fun hasMore(): Boolean = pos < tokens.size

        fun peek(): Token? = tokens.getOrNull(pos)

        private fun consume(): Token = tokens[pos++]

        private fun expect(type: String): Token {
            val t = peek() ?: throw StrategyException.DslSyntaxException("Expected $type but EOF")
            if (t.type != type) {
                throw StrategyException.DslSyntaxException("Expected $type but got ${t.type}=${t.value} @${t.position}")
            }
            return consume()
        }

        private fun match(vararg types: String): Boolean {
            val t = peek() ?: return false
            return t.type in types
        }

        // ---------------------------------------------------------------------
        // Source: parser.py:L412 _parse_or_expression — 우선순위 1 (최저)
        // ---------------------------------------------------------------------
        fun parseOrExpression(): DslNode {
            var left = parseAndExpression()
            while (match("OR")) {
                consume()
                val right = parseAndExpression()
                left =
                    CompositeCondition(
                        left = left,
                        operator = LogicalOperator.OR,
                        right = right,
                    )
            }
            return left
        }

        // ---------------------------------------------------------------------
        // Source: parser.py:L425 _parse_and_expression — 우선순위 2
        // ---------------------------------------------------------------------
        private fun parseAndExpression(): DslNode {
            var left = parseCondition()
            while (match("AND")) {
                consume()
                val right = parseCondition()
                left =
                    CompositeCondition(
                        left = left,
                        operator = LogicalOperator.AND,
                        right = right,
                    )
            }
            return left
        }

        // ---------------------------------------------------------------------
        // Source: parser.py:L438 _parse_condition — 우선순위 3 (비교 operator)
        // ConditionType 결정 룰: parser.py:L361-L390 LOCK.
        // ---------------------------------------------------------------------
        private fun parseCondition(): DslNode {
            val left = parseArithmetic()
            // operator 분기 (9종)
            val opType = peek()?.type
            val operator =
                when (opType) {
                    "GT" -> Operator.GT
                    "LT" -> Operator.LT
                    "GTE" -> Operator.GTE
                    "LTE" -> Operator.LTE
                    "EQ" -> Operator.EQ
                    "NEQ" -> Operator.NEQ
                    "CROSSES_ABOVE" -> Operator.CROSSES_ABOVE
                    "CROSSES_BELOW" -> Operator.CROSSES_BELOW
                    "BREAKS" -> Operator.BREAKS
                    else -> return left // condition 아님 — arithmetic 결과만
                }
            consume()
            val right = parseArithmetic()

            // ConditionType 결정 (parser.py:L361-L390 LOCK)
            val conditionType =
                when {
                    operator == Operator.CROSSES_ABOVE || operator == Operator.CROSSES_BELOW -> ConditionType.CROSSOVER
                    operator == Operator.BREAKS -> ConditionType.BREAKOUT
                    isPatternIndicator(left) -> ConditionType.PATTERN
                    else -> ConditionType.THRESHOLD
                }

            return Condition(left = left, operator = operator, right = right, conditionType = conditionType)
        }

        // ---------------------------------------------------------------------
        // Source amend: 일반 산술 우선순위 — +,- 는 *,/ 보다 낮게 결합한다.
        // ---------------------------------------------------------------------
        private fun parseArithmetic(): DslNode {
            var left = parseTerm()
            while (match("ADD", "SUB")) {
                val opTok = consume()
                val op = if (opTok.type == "ADD") ArithmeticOperator.ADD else ArithmeticOperator.SUB
                val right = parseTerm()
                left = ArithmeticExpr(left, op, right)
            }
            return left
        }

        // ---------------------------------------------------------------------
        // Source amend: 일반 산술 우선순위 — *,/ 를 factor에 먼저 결합한다.
        // ---------------------------------------------------------------------
        private fun parseTerm(): DslNode {
            var left = parseFactor()
            while (match("MUL", "DIV")) {
                val opTok = consume()
                val op = if (opTok.type == "MUL") ArithmeticOperator.MUL else ArithmeticOperator.DIV
                val right = parseFactor()
                left = ArithmeticExpr(left, op, right)
            }
            return left
        }

        // ---------------------------------------------------------------------
        // Source: parser.py:L470-L530 _parse_factor — 우선순위 6 (단항 -, NUMBER, IDENTIFIER, ( ))
        // ---------------------------------------------------------------------
        private fun parseFactor(): DslNode {
            val t = peek() ?: throw StrategyException.DslSyntaxException("Unexpected EOF in factor")

            // 단항 마이너스 (parser.py:L474 — `returns(1) > -3.5` 정합)
            if (t.type == "SUB") {
                consume()
                val inner = parseFactor()
                return when (inner) {
                    is LiteralValue -> LiteralValue(inner.value.negate(), inner.isPercent)
                    else ->
                        ArithmeticExpr(
                            left = LiteralValue(BigDecimal.ZERO),
                            operator = ArithmeticOperator.SUB,
                            right = inner,
                        )
                }
            }

            // 그룹 ( )
            if (t.type == "LPAREN") {
                consume()
                val node = parseOrExpression()
                expect("RPAREN")
                return node
            }

            // NUMBER + optional PERCENT (parser.py:L218 isPercent)
            if (t.type == "NUMBER") {
                consume()
                val value = BigDecimal(t.value)
                val isPercent = match("PERCENT")
                if (isPercent) consume()
                return LiteralValue(value, isPercent)
            }

            // IDENTIFIER — IndicatorRef 또는 캔들 직접 접근
            if (t.type == "IDENTIFIER") {
                consume()
                val name = t.value
                // 함수 호출 형식 `name(args)` ?
                if (match("LPAREN")) {
                    consume()
                    val args = mutableListOf<Any>()
                    if (!match("RPAREN")) {
                        args.add(parseArg())
                        while (match("COMMA")) {
                            consume()
                            args.add(parseArg())
                        }
                    }
                    expect("RPAREN")
                    return IndicatorRef(name, args)
                }
                // bare identifier — 캔들 직접 접근 (close · prev_close · ...)
                return IndicatorRef(name, emptyList())
            }

            throw StrategyException.DslSyntaxException("Unexpected token in factor: ${t.type}=${t.value} @${t.position}")
        }

        /**
         * IndicatorRef 인자 — NUMBER | IDENTIFIER (`up`·`down` 같은 string).
         */
        private fun parseArg(): Any {
            val t = peek() ?: throw StrategyException.DslSyntaxException("Unexpected EOF in arg")
            return when (t.type) {
                "NUMBER" -> {
                    consume()
                    val value = BigDecimal(t.value)
                    // KIS는 Int param 위주 — period 등은 정수
                    if (t.value.contains('.')) value else value.toInt()
                }
                "SUB" -> {
                    consume()
                    val n = expect("NUMBER")
                    val v = BigDecimal(n.value).negate()
                    if (n.value.contains('.')) v else v.toInt()
                }
                "IDENTIFIER" -> {
                    consume()
                    t.value
                }
                else -> throw StrategyException.DslSyntaxException("Unexpected arg token: ${t.type}=${t.value} @${t.position}")
            }
        }
    }

    /**
     * `consecutive(...)` · `pattern(...)` · `pattern_detected(...)` IndicatorRef 판별
     * (parser.py:L378-L380 정합 + T-PAT PR-39 magicJar alias 추가).
     */
    private fun isPatternIndicator(node: DslNode): Boolean =
        node is IndicatorRef && (node.name == "consecutive" || node.name == "pattern" || node.name == "pattern_detected")

    // =====================================================================
    // Static 검증 visitor (ADR 0047 §2-1)
    // =====================================================================

    private inner class StaticVisitor {
        var maxDepth = 0
        var nodeCount = 0
        var indicatorCallCount = 0
        var unknownSymbol: String? = null

        fun walk(
            node: DslNode,
            depth: Int,
        ) {
            nodeCount++
            if (depth > maxDepth) maxDepth = depth
            // 한도 검증 — early exit (ADR 0047 §2-1 visit 한도 doubling 방지)
            if (nodeCount > nodeCountLimit + 1) return
            if (depth > astDepthLimit + 1) return

            when (node) {
                is IndicatorRef -> {
                    indicatorCallCount++
                    if (unknownSymbol == null && !ALLOWED_IDENTIFIERS.contains(node.name)) {
                        unknownSymbol = node.name
                    }
                }
                is LiteralValue -> {
                    // leaf
                }
                is ArithmeticExpr -> {
                    walk(node.left, depth + 1)
                    walk(node.right, depth + 1)
                }
                is Condition -> {
                    walk(node.left, depth + 1)
                    walk(node.right, depth + 1)
                }
                is CompositeCondition -> {
                    walk(node.left, depth + 1)
                    walk(node.right, depth + 1)
                }
            }
        }
    }

    companion object {
        /**
         * YAML mapper — jackson-dataformat-yaml + Kotlin module.
         *
         * spec §8-8 (Spring Boot 3.3 기본 포함).
         */
        private val YAML_MAPPER: ObjectMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

        /**
         * 21 token patterns LOCK — KIS parser.py:L304-L322 정확 인용 (자체 추론 0).
         *
         * 우선순위 중요:
         * - 다중자 operator (`>=`·`<=`·`!=`·`==`·`crosses_above` 등)는 단일자 (`>`·`<`·`=`)보다 먼저
         * - keyword (`AND`·`OR`)는 IDENTIFIER보다 먼저
         */
        private val TOKEN_PATTERNS: List<Pair<String, Regex>> =
            listOf(
                "LPAREN" to Regex("""\("""),
                "RPAREN" to Regex("""\)"""),
                "COMMA" to Regex(""","""),
                "PERCENT" to Regex("""%"""),
                "CROSSES_ABOVE" to Regex("""crosses_above"""),
                "CROSSES_BELOW" to Regex("""crosses_below"""),
                "BREAKS" to Regex("""breaks"""),
                "AND" to Regex("""(?:AND|and)\b"""),
                "OR" to Regex("""(?:OR|or)\b"""),
                "GTE" to Regex(""">="""),
                "LTE" to Regex("""<="""),
                "NEQ" to Regex("""!="""),
                "EQ" to Regex("""=="""),
                "GT" to Regex(""">"""),
                "LT" to Regex("""<"""),
                "MUL" to Regex("""\*"""),
                "DIV" to Regex("""/"""),
                "ADD" to Regex("""\+"""),
                "SUB" to Regex("""-"""),
                "NUMBER" to Regex("""\d+\.?\d*"""),
                "IDENTIFIER" to Regex("""[a-zA-Z_][a-zA-Z0-9_]*"""),
                "WHITESPACE" to Regex("""\s+"""),
            )

        /**
         * 화이트리스트 (ADR 0047 §2-1-A) — 80지표 + DSL alias + 캔들 + 패턴 + 자체 함수.
         *
         * Source: pr-35-kis-dsl-raw-verification.md §3 + spec §5-5 LOCK.
         */
        private val ALLOWED_IDENTIFIERS: Set<String> =
            buildSet {
                // 80지표 (T-IND IndicatorCatalog)
                IndicatorCatalog.ALL.forEach { add(it.id) }
                // DSL alias 8종 (multi-output)
                add("macd_signal")
                add("macd_hist")
                add("bb_upper")
                add("bb_lower")
                add("bb_middle")
                add("stoch_k")
                add("stoch_d")
                add("volume_ma")
                // Smart money aliases — FVG / Order Block multi-output
                add("fvg_active")
                add("fvg_bullish_active")
                add("fvg_bearish_active")
                add("fvg_bullish_top")
                add("fvg_bullish_bottom")
                add("fvg_bullish_mid")
                add("fvg_bullish_fill_pct")
                add("fvg_bearish_top")
                add("fvg_bearish_bottom")
                add("fvg_bearish_mid")
                add("fvg_bearish_fill_pct")
                add("ob_active")
                add("ob_bullish_active")
                add("ob_bearish_active")
                add("ob_bullish_top")
                add("ob_bullish_bottom")
                add("ob_bullish_mid")
                add("ob_bullish_fill_pct")
                add("ob_bearish_top")
                add("ob_bearish_bottom")
                add("ob_bearish_mid")
                add("ob_bearish_fill_pct")
                add("ob_bos_direction")
                // 캔들 직접 접근 6종 (특수 케이스 — Strategy Builder OHLCV 계약 정합)
                add("open")
                add("close")
                add("prev_close")
                add("high")
                add("low")
                add("volume")
                // 자체 함수 4종
                add("change")
                add("highest")
                add("lowest")
                add("returns")
                // 패턴 함수 3종 (T-PAT PR-39 — JvmPatternRecognizer 활성화)
                // - `consecutive("up"|"down")` — magicJar own (KIS 미존재)
                // - `pattern("name")` — KIS DSL 본체 호환
                // - `pattern_detected("name")` — magicJar alias (T-PAT PR-39 추가) — `pattern(...)` 동치 라우팅
                add("consecutive")
                add("pattern")
                add("pattern_detected")
                // KIS 자체 (raw verification §3 정합)
                add("disparity")
                add("volatility")
            }

        /**
         * 파서가 실제로 파싱·검증을 통과시키는 식별자 화이트리스트(읽기 전용 공개).
         *
         * AI 전략 생성 메타 프롬프트가 "실제로 파싱 가능한 문법"만 Claude에게 주입하도록 이 집합을
         * 코드에서 직접 추출한다. 지표가 추가/삭제되면 [ALLOWED_IDENTIFIERS]와 함께 자동 정합된다.
         */
        val SUPPORTED_IDENTIFIERS: Set<String> get() = ALLOWED_IDENTIFIERS
    }
}
