package kr.spdaewoon.magicjar.api.order

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * V80 키 사슬 정비 마이그레이션(`V80__order_request_order_trigger.sql`) 구조 무결성 테스트.
 *
 * ### 검증 대상 (ADR 0115 §2-P2-⑥ · spec 2026-06-10-execution-ledger-integrity §2-B-2 불변식 1)
 * - `order_trigger`·`trigger_ref_id` 컬럼 add-only(nullable — legacy backward compat).
 * - 허용 enum CHECK 제약(6값) + NULL 허용.
 * - 기존 행 best-effort backfill 분류 절(source·signal_id 조합 → 6 trigger).
 *
 * Testcontainers 없이 SQL 파일 텍스트만 검증(가벼운 단위 테스트 — V76 선례 정합).
 * AI 호출 0 · mock 0.
 */
class V80OrderTriggerMigrationTest {
    private val sqlText: String by lazy {
        val f = locateMigration()
        assertTrue(f.exists(), "V80 마이그레이션 파일을 찾지 못했습니다: ${f.absolutePath}")
        f.readText()
    }

    @Test
    fun `V80은 order_trigger·trigger_ref_id 컬럼을 add-only nullable로 추가한다`() {
        assertTrue(
            Regex("ADD COLUMN IF NOT EXISTS order_trigger VARCHAR", RegexOption.IGNORE_CASE).containsMatchIn(sqlText),
            "order_trigger VARCHAR 컬럼 추가 절이 없습니다.",
        )
        assertTrue(
            Regex("ADD COLUMN IF NOT EXISTS trigger_ref_id BIGINT", RegexOption.IGNORE_CASE).containsMatchIn(sqlText),
            "trigger_ref_id BIGINT 컬럼 추가 절이 없습니다.",
        )
        // nullable backward compat — NOT NULL 강제 0(legacy 행이 위반하면 안 됨).
        assertTrue(
            !Regex("order_trigger\\s+VARCHAR\\([0-9]+\\)\\s+NOT NULL", RegexOption.IGNORE_CASE).containsMatchIn(sqlText),
            "order_trigger를 NOT NULL로 추가하면 legacy 행이 위반합니다(점진 전환 위반).",
        )
    }

    @Test
    fun `V80 CHECK 제약은 6개 enum 값을 허용하고 NULL을 허용한다`() {
        REQUIRED_ENUM_VALUES.forEach { value ->
            assertTrue(
                sqlText.contains("'$value'"),
                "CHECK 제약 허용 값에 '$value'가 없습니다(OrderTrigger enum 정합).",
            )
        }
        assertTrue(
            Regex("order_trigger IS NULL OR order_trigger IN", RegexOption.IGNORE_CASE).containsMatchIn(sqlText),
            "CHECK 제약이 NULL을 허용하지 않습니다(legacy 미분류 backward compat 위반).",
        )
    }

    @Test
    fun `V80은 기존 행을 source·signal_id 조합으로 6 trigger로 backfill한다 (0-sentinel 폐지)`() {
        // 핵심 분류 분기 — spec §2-B-1 4 단절 해소.
        assertTrue(sqlText.contains("source = 'EXTERNAL' THEN 'EXTERNAL'"), "EXTERNAL backfill 분기가 없습니다.")
        assertTrue(sqlText.contains("source = 'MANUAL' THEN 'MANUAL'"), "MANUAL backfill 분기가 없습니다.")
        assertTrue(
            Regex("source = 'AUTO' AND signal_id > 0 THEN 'STRATEGY_SIGNAL'").containsMatchIn(sqlText),
            "AUTO+signal_id>0 → STRATEGY_SIGNAL backfill 분기가 없습니다.",
        )
        assertTrue(
            sqlText.contains("'STOP_LOSS'") && sqlText.contains("auto-sl"),
            "auto-sl idempotency_key → STOP_LOSS backfill 분기가 없습니다.",
        )
        assertTrue(sqlText.contains("ELSE 'AUTO_EXIT'"), "AUTO 청산 fallback(AUTO_EXIT) 분기가 없습니다.")
        // trigger_ref_id 복원 — 0 sentinel은 NULL 유지(NULLIF).
        assertTrue(
            Regex("NULLIF\\(signal_id, 0\\)").containsMatchIn(sqlText) &&
                Regex("NULLIF\\(strategy_id, 0\\)").containsMatchIn(sqlText),
            "trigger_ref_id를 NULLIF(0→NULL)로 복원하는 절이 없습니다(sentinel 위장 폐지).",
        )
        // 기존 분류 보존 — 이미 분류된 행은 덮지 않음.
        assertTrue(
            Regex("WHERE order_trigger IS NULL", RegexOption.IGNORE_CASE).containsMatchIn(sqlText),
            "backfill UPDATE가 WHERE order_trigger IS NULL로 멱등 가드되지 않았습니다.",
        )
    }

    private fun locateMigration(): File {
        val rel = "api/src/main/resources/db/migration/$MIGRATION_NAME"
        val relInModule = "src/main/resources/db/migration/$MIGRATION_NAME"
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            File(dir, rel).let { if (it.exists()) return it }
            File(dir, relInModule).let { if (it.exists()) return it }
            dir = dir.parentFile
        }
        return File(relInModule)
    }

    private companion object {
        const val MIGRATION_NAME = "V80__order_request_order_trigger.sql"
        val REQUIRED_ENUM_VALUES =
            listOf("STRATEGY_SIGNAL", "AUTO_EXIT", "STOP_LOSS", "PARTIAL_TAKE_PROFIT", "MANUAL", "EXTERNAL")
    }
}
