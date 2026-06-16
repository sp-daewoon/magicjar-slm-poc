package kr.spdaewoon.magicjar.api.strategyresearch

import kr.spdaewoon.magicjar.strategy.KisYamlDslParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * V76 단타 프리셋 검증·승격 시드(`V76__daytrade_preset_validation_promote.sql`) 무결성 테스트.
 *
 * ### 검증 리포트 정합 (T-20260610-0222)
 * 출처: `_workspace/strategy/2026-06-10-v76-preset-validation-report.md`.
 * R3 IS/OOS 검증 통과 1종(`DAYTRADE_GAP_AND_GO`) 승격 + `DAYTRADE_VOLATILITY_EXPANSION` 비활성화.
 *
 * ### 본 테스트가 막는 회귀 (graceful-skip 함정 — 검증 리포트 §2-2)
 * 시드 row의 `entry_dsl`이 파서 화이트리스트는 통과하나 `DslIdentifierMapper.toIndicatorSpec`의
 * CATALOG_IDS에 미등록(`highest`·`lowest`·`returns`·`change`)이면 `validateStatic`에서
 * `UnknownDslSymbolException` → 발굴 graceful skip(매칭 0)으로 **조용히 죽는다**.
 * 이 테스트는 V76이 추가/수정하는 entry_dsl을 **실 파서로 parse + validateStatic** 통과시켜
 * 등록 식별자만 썼음을 컴파일 타임이 아닌 데이터(SQL) 레벨로 보증한다.
 *
 * Testcontainers 없이 SQL 파일 텍스트 + 실 strategy-engine 파서만 사용(가벼운 단위 테스트).
 * AI 호출 0 · mock 0(파서는 실 구현).
 */
class V76DaytradePromoteSeedTest {
    private val parser = KisYamlDslParser(astDepthLimit = 32, nodeCountLimit = 10000, indicatorCallLimit = 50)

    private val sqlText: String by lazy {
        val f = locateMigration()
        assertTrue(f.exists(), "V76 마이그레이션 파일을 찾지 못했습니다: ${f.absolutePath}")
        f.readText()
    }

    @Test
    fun `V76 GAP_AND_GO entry_dsl은 파서 화이트리스트 + 정적검증을 통과한다 (graceful-skip 함정 회피)`() {
        val dsl = GAP_AND_GO_DSL
        // SQL 파일에 정확히 이 식이 박혀 있어야 한다(리포트 §4-3 LOCK).
        assertTrue(
            sqlText.contains(dsl),
            "V76 SQL에 DAYTRADE_GAP_AND_GO entry_dsl이 검증 리포트와 일치하지 않습니다.",
        )

        // 실 파서 parse → validateStatic. 미등록 식별자면 여기서 UnknownDslSymbolException으로 터진다.
        val ast = parser.parse(dsl)
        val complexity = parser.validateStatic(ast)

        // 정적 검증 통과(예외 없음) + 해석 가능한 식이어야 한다.
        assertTrue(complexity.nodeCount > 0, "AST 노드가 0이면 파싱이 비어 있는 것입니다.")
        assertTrue(complexity.astDepth in 1..32, "AST depth가 한도를 벗어났습니다: ${complexity.astDepth}")

        // 엔진 무력 식별자(검증 리포트 §2-2·§5-1)를 절대 쓰지 않는다.
        FORBIDDEN_IDENTIFIERS.forEach { forbidden ->
            assertFalse(
                Regex("\\b$forbidden\\s*\\(").containsMatchIn(dsl),
                "V76 entry_dsl이 엔진 미등록/무력 식별자 '$forbidden'을 사용합니다(graceful-skip 위험).",
            )
        }
    }

    @Test
    fun `V76 시드는 GAP_AND_GO INSERT 1건 + VOLATILITY_EXPANSION 비활성 UPDATE 1건을 갖는다`() {
        // 신규 승격 — sort_order 230(V75 200~220 뒤), is_builtin TRUE, ON CONFLICT DO NOTHING.
        assertTrue(sqlText.contains("'DAYTRADE_GAP_AND_GO'"), "GAP_AND_GO INSERT가 없습니다.")
        assertTrue(sqlText.contains("230, TRUE, TRUE"), "GAP_AND_GO sort_order 230 / is_active / is_builtin 값이 다릅니다.")
        assertTrue(
            sqlText.contains("ON CONFLICT (candidate_key) DO NOTHING"),
            "ON CONFLICT DO NOTHING(재배포 안정성, V74·V75 선례)이 없습니다.",
        )

        // V75-2 비활성화 — 양 레짐 손실 실측 근거(검증 리포트 §3).
        assertTrue(
            Regex("UPDATE\\s+strategy_research_candidate", RegexOption.IGNORE_CASE).containsMatchIn(sqlText),
            "VOLATILITY_EXPANSION 비활성 UPDATE가 없습니다.",
        )
        assertTrue(
            sqlText.contains("is_active = FALSE") &&
                sqlText.contains("WHERE candidate_key = 'DAYTRADE_VOLATILITY_EXPANSION'"),
            "VOLATILITY_EXPANSION을 is_active=FALSE로 비활성화하는 절이 없습니다.",
        )
    }

    @Test
    fun `검증에서 탈락한 가설 key는 V76 시드에 INSERT되지 않는다 (억지 승격 0)`() {
        // 통과 못 한 가설(H2~H8 신규 key 등)은 시드 추가 0 — 0개 통과면 0개 시드 철학 LOCK.
        REJECTED_KEYS.forEach { key ->
            assertFalse(
                sqlText.contains("'$key'"),
                "탈락 가설 key '$key'가 V76 시드에 들어갔습니다(억지 승격 금지).",
            )
        }
        // 신규 INSERT는 정확히 1개 candidate_key(GAP_AND_GO)만.
        val insertedKeys = INSERTED_KEY_REGEX.findAll(sqlText).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf("DAYTRADE_GAP_AND_GO"),
            insertedKeys - "DAYTRADE_VOLATILITY_EXPANSION", // UPDATE 절 WHERE의 key는 INSERT가 아님
            "V76 신규 INSERT는 GAP_AND_GO 1건이어야 합니다. 실제: $insertedKeys",
        )
    }

    private fun locateMigration(): File {
        // 모듈 루트(api/) 또는 프로젝트 루트 어디서 실행돼도 찾도록 상위 탐색.
        val rel = "api/src/main/resources/db/migration/$MIGRATION_NAME"
        val relInModule = "src/main/resources/db/migration/$MIGRATION_NAME"
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            File(dir, rel).let { if (it.exists()) return it }
            File(dir, relInModule).let { if (it.exists()) return it }
            dir = dir.parentFile
        }
        // 마지막 시도 — 현재 작업 디렉토리 기준.
        return File(relInModule)
    }

    private companion object {
        const val MIGRATION_NAME = "V76__daytrade_preset_validation_promote.sql"

        // 검증 리포트 §4-3 LOCK — 승격 식.
        const val GAP_AND_GO_DSL =
            "open >= prev_close * 1.025 and volume > volume_ma(20) * 2.5 and close * volume > 16000000000 and close > 2000"

        // 검증 리포트 §2-2·§5-1 — 엔진 미등록/무력 식별자.
        val FORBIDDEN_IDENTIFIERS =
            listOf("highest", "lowest", "returns", "change", "donchian_upper", "donchian_lower", "trade_intensity", "vwap")

        // 검증에서 탈락한 가설(신규 승격 금지) — H1만 통과.
        val REJECTED_KEYS =
            listOf(
                "DAYTRADE_VOLUME_BREAKOUT",
                "DAYTRADE_MOMENTUM_CONT",
                "DAYTRADE_MA_PULLBACK",
                "DAYTRADE_SQUEEZE_EXPANSION",
                "DAYTRADE_RANGE_BREAKOUT",
                "DAYTRADE_TURNOVER_SURGE",
                "DAYTRADE_STRONG_CLOSE",
            )

        val INSERTED_KEY_REGEX = Regex("'(DAYTRADE_[A-Z_]+)'")
    }
}
