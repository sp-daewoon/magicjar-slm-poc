package kr.spdaewoon.magicjar.consumer.persistence

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.signal.Signal
import kr.spdaewoon.magicjar.domain.signal.SignalSide
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import java.math.BigDecimal
import java.time.Instant as JavaInstant

/**
 * ConsumerSignalJpaAdapter unit test (architect ★★ — 자본 path test coverage).
 *
 * 저장된 signal id 가 Kafka payload 에 포함되어야 risk listener 가 signal.generated.v1 을 정상 소비.
 * spec 인용: ADR 0066 §7-1 + §7-2 (SignalRepository) — 실시간 hot path consumer JVM 단독 구현.
 */
class ConsumerSignalJpaAdapterTest {
    private val signalJpaRepository = mockk<ConsumerSignalJpaRepository>()
    private val adapter = ConsumerSignalJpaAdapter(signalJpaRepository = signalJpaRepository)

    private val boundaryTs = Instant.parse("2026-05-20T00:30:00Z")

    @Test
    fun `signal 저장 시 DB INSERT 후 채번된 id 가 도메인으로 반환된다`() {
        val signal = newSignal(id = null, strength = 70, reason = "MA_CROSS")
        val savedEntity = slot<ConsumerSignalJpaEntity>()

        every { signalJpaRepository.save(capture(savedEntity)) } answers {
            entityCopy(savedEntity.captured, newId = 99L)
        }

        val persisted = adapter.save(signal)

        assertThat(persisted.id).isEqualTo(99L)
        assertThat(persisted.strategyId).isEqualTo(42L)
        assertThat(persisted.userId).isEqualTo("1234")
        assertThat(persisted.code).isEqualTo("005930")
        assertThat(persisted.interval).isEqualTo(CandleInterval.FIVE_MIN)
        assertThat(persisted.ts).isEqualTo(boundaryTs)
        assertThat(persisted.side).isEqualTo(SignalSide.BUY)
        assertThat(persisted.strength).isEqualTo(70)
        assertThat(persisted.reason).isEqualTo("MA_CROSS")
        assertThat(persisted.priceAtSignal.toPlainString()).isEqualTo("150.0000")
        // entity 매핑 확인 — domain SignalSide → entity side string
        assertThat(savedEntity.captured.strategyId).isEqualTo(42L)
        assertThat(savedEntity.captured.side).isEqualTo("BUY")
        assertThat(savedEntity.captured.interval).isEqualTo("5m")
        assertThat(savedEntity.captured.ts).isEqualTo(boundaryTs.toJavaInstant())
    }

    @Test
    fun `이미 id 가 채번된 signal 저장은 idempotent 하게 동일 id 를 보존한다`() {
        val signal = newSignal(id = 55L, strength = 90, reason = "RSI_OVERSOLD")
        val savedEntity = slot<ConsumerSignalJpaEntity>()

        every { signalJpaRepository.save(capture(savedEntity)) } answers {
            entityCopy(savedEntity.captured, newId = 55L)
        }

        val persisted = adapter.save(signal)

        assertThat(persisted.id).isEqualTo(55L)
        assertThat(savedEntity.captured.id).isEqualTo(55L)
        verify(exactly = 1) { signalJpaRepository.save(any()) }
    }

    @Test
    fun `DataIntegrityViolationException 발생 시 기존 signal 을 조회해 domain 으로 반환한다`() {
        val signal = newSignal(id = null, strength = 60, reason = "DUP")
        val existing =
            existingEntity(
                id = 31L,
                strategyId = signal.strategyId,
                code = signal.code,
                interval = signal.interval.code,
                ts = signal.ts.toJavaInstant(),
            )

        every { signalJpaRepository.save(any()) } throws DataIntegrityViolationException("UQ violation")
        every {
            signalJpaRepository.findByStrategyIdAndCodeAndIntervalAndTs(
                signal.strategyId,
                signal.code,
                signal.interval.code,
                signal.ts.toJavaInstant(),
            )
        } returns existing

        val persisted = adapter.save(signal)

        assertThat(persisted.id).isEqualTo(31L)
        assertThat(persisted.side).isEqualTo(SignalSide.BUY)
        verify(exactly = 1) { signalJpaRepository.save(any()) }
        verify(exactly = 1) {
            signalJpaRepository.findByStrategyIdAndCodeAndIntervalAndTs(any(), any(), any(), any())
        }
    }

    @Test
    fun `DataIntegrityViolationException 발생 + 기존 signal 미존재 시 원본 signal 을 그대로 반환한다`() {
        val signal = newSignal(id = null, strength = 60, reason = "DUP_MISSING")

        every { signalJpaRepository.save(any()) } throws DataIntegrityViolationException("UQ violation")
        every {
            signalJpaRepository.findByStrategyIdAndCodeAndIntervalAndTs(
                signal.strategyId,
                signal.code,
                signal.interval.code,
                signal.ts.toJavaInstant(),
            )
        } returns null

        val persisted = adapter.save(signal)

        // graceful fallback — 원본 signal 그대로 반환 (id null 유지)
        assertThat(persisted).isEqualTo(signal)
        assertThat(persisted.id).isNull()
    }

    @Test
    fun `findByDedupeKey 는 entity 조회 결과를 domain Signal 로 변환해 반환한다`() {
        val ts = Instant.parse("2026-05-20T01:00:00Z")
        every {
            signalJpaRepository.findByStrategyIdAndCodeAndIntervalAndTs(
                strategyId = 42L,
                code = "005930",
                interval = "5m",
                ts = ts.toJavaInstant(),
            )
        } returns
            existingEntity(
                id = 73L,
                strategyId = 42L,
                code = "005930",
                interval = "5m",
                ts = ts.toJavaInstant(),
                side = "SELL",
                strength = 80,
                reason = "TRAILING_STOP",
            )

        val found = adapter.findByDedupeKey(42L, "005930", CandleInterval.FIVE_MIN, ts)

        assertThat(found).isNotNull
        assertThat(found!!.id).isEqualTo(73L)
        assertThat(found.side).isEqualTo(SignalSide.SELL)
        assertThat(found.strength).isEqualTo(80)
        assertThat(found.reason).isEqualTo("TRAILING_STOP")
        assertThat(found.ts).isEqualTo(ts)
    }

    @Test
    fun `findByDedupeKey 결과가 null 이면 null 을 반환한다`() {
        val ts = Instant.parse("2026-05-20T02:00:00Z")
        every {
            signalJpaRepository.findByStrategyIdAndCodeAndIntervalAndTs(any(), any(), any(), any())
        } returns null

        val found = adapter.findByDedupeKey(42L, "005930", CandleInterval.FIVE_MIN, ts)

        assertThat(found).isNull()
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    private fun newSignal(
        id: Long?,
        strength: Int,
        reason: String,
    ): Signal =
        Signal(
            id = id,
            strategyId = 42L,
            userId = "1234",
            code = "005930",
            interval = CandleInterval.FIVE_MIN,
            ts = boundaryTs,
            side = SignalSide.BUY,
            strength = strength,
            reason = reason,
            priceAtSignal = BigDecimal("150.0000"),
            contextJson = null,
            createdAt = boundaryTs,
        )

    private fun entityCopy(
        source: ConsumerSignalJpaEntity,
        newId: Long,
    ): ConsumerSignalJpaEntity =
        ConsumerSignalJpaEntity(
            id = newId,
            strategyId = source.strategyId,
            userId = source.userId,
            code = source.code,
            interval = source.interval,
            ts = source.ts,
            side = source.side,
            strength = source.strength,
            reason = source.reason,
            price = source.price,
            ruleSignal = source.ruleSignal,
            contextJson = source.contextJson,
            createdAt = JavaInstant.parse("2026-05-20T00:30:01Z"),
            riskStatus = source.riskStatus,
        )

    private fun existingEntity(
        id: Long,
        strategyId: Long,
        code: String,
        interval: String,
        ts: JavaInstant,
        side: String = "BUY",
        strength: Int = 70,
        reason: String? = "EXISTING",
    ): ConsumerSignalJpaEntity =
        ConsumerSignalJpaEntity(
            id = id,
            strategyId = strategyId,
            userId = "1234",
            code = code,
            interval = interval,
            ts = ts,
            side = side,
            strength = strength,
            reason = reason,
            price = BigDecimal("150.0000"),
            ruleSignal = null,
            contextJson = null,
            createdAt = JavaInstant.parse("2026-05-20T00:30:01Z"),
            riskStatus = "PENDING",
        )

    // unused safety — kotlinx Instant ↔ java Instant 변환 정합성을 명시적으로 보장한다
    @Suppress("unused")
    private fun assertConversion() {
        val now = Instant.parse("2026-05-20T00:00:00Z")
        assertThat(now.toJavaInstant().toKotlinInstant()).isEqualTo(now)
    }
}
