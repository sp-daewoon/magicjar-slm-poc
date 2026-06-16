package kr.spdaewoon.magicjar.api.scenario

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ScenarioReplayBoundaryTest {
    @Test
    fun `api scenario package does not reference live trading runtime`() {
        val sourceRoot =
            Path
                .of(System.getProperty("user.dir"))
                .resolve("src/main/kotlin/kr/spdaewoon/magicjar/api/scenario")

        Files.exists(sourceRoot) shouldBe true

        val violations = mutableListOf<String>()
        val stream = Files.walk(sourceRoot)
        try {
            stream
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .forEach { path ->
                    val text = Files.readString(path)
                    FORBIDDEN_TOKENS
                        .filter { token -> text.contains(token) }
                        .forEach { token -> violations += "${sourceRoot.relativize(path)} contains $token" }
                }
        } finally {
            stream.close()
        }

        violations.shouldBeEmpty()
    }

    @Test
    fun `scenario replay migration creates only scenario namespace tables`() {
        val migration =
            Path
                .of(System.getProperty("user.dir"))
                .resolve("src/main/resources/db/migration/V63__scenario_replay.sql")
        val text = Files.readString(migration)

        SQL_FORBIDDEN_TABLES
            .filter { table -> text.contains("CREATE TABLE IF NOT EXISTS $table") || text.contains("REFERENCES $table") }
            .shouldBeEmpty()
    }

    companion object {
        private val FORBIDDEN_TOKENS =
            listOf(
                "kr.spdaewoon.magicjar.consumer",
                "kr.spdaewoon.magicjar.api.messaging.order",
                "kr.spdaewoon.magicjar.api.persistence.order",
                "kr.spdaewoon.magicjar.api.persistence.risk",
                "kr.spdaewoon.magicjar.api.persistence.position",
                "OrderRouterKafkaListener",
                "KisOrderHttpAdapter",
                "KisOrderPort",
                "KisExecutionWsListener",
                "KafkaTemplate",
                "order.intent.v1",
                "order.requested.v1",
                "order.filled.v1",
                "order.rejected.v1",
                "risk.violation.v1",
                "signal.generated.v1",
                "TTTC0012U",
                "TTTC0011U",
                "VTTC0012U",
                "VTTC0011U",
                "magicjar.scenario-replay.enabled",
                "MAGICJAR_SCENARIO_REPLAY_ENABLED",
            )

        private val SQL_FORBIDDEN_TABLES =
            listOf(
                "order_request",
                "order_execution",
                "order_audit_event",
                "risk_event",
                "position",
                "trading_journal",
                "performance_summary",
            )
    }
}
