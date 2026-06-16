package kr.spdaewoon.magicjar.domain.scenario

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ScenarioBoundaryTest {
    @Test
    fun `scenario domain keeps live trading boundary out`() {
        val sourceRoot =
            Path
                .of(System.getProperty("user.dir"))
                .resolve("src/main/kotlin/kr/spdaewoon/magicjar/domain/scenario")

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

    companion object {
        private val FORBIDDEN_TOKENS =
            listOf(
                "kr.spdaewoon.magicjar.api.",
                "kr.spdaewoon.magicjar.consumer.",
                "kr.spdaewoon.magicjar.domain.order",
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
                "order_request",
                "order_execution",
                "order_audit_event",
                "risk_event",
                "trading_journal",
                "performance_summary",
            )
    }
}
