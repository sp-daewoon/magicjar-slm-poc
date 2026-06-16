package kr.spdaewoon.magicjar.api.service.signal

import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.persistence.signal.SignalJpaAdapter
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaEntity
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaRepository
import kr.spdaewoon.magicjar.domain.signal.Signal
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * 시그널 + 발행 전략 메타 결합 결과 — 전략명·매수/매도 조건 enrich.
 * 시그널만으로는 "어떤 전략이 왜 발행했는지" 추적 불가하므로 strategy 메타를 결합한다.
 */
data class EnrichedSignal(
    val signal: Signal,
    val codeName: String?,
    val strategyName: String?,
    val strategyNameKo: String?,
    val buyCondition: String?,
    val sellCondition: String?,
)

/**
 * 시그널 조회 서비스 (ADR 0066 §5-3 + §7-2 단계 5).
 *
 * SignalController에서 사용. SignalJpaAdapter 직접 의존.
 * 포트를 통한 간접 접근은 page 조회가 domain port 범위를 벗어나므로 adapter 직접 사용.
 * 응답에 발행 전략 메타(name·dsl_entry·dsl_exit)를 batch 결합한다.
 *
 * // AI 호출 0 — T-SIG 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8)
 */
@Service
class SignalQueryService(
    private val signalJpaAdapter: SignalJpaAdapter,
    private val instrumentJpaRepository: InstrumentJpaRepository,
    private val strategyJpaRepository: StrategyJpaRepository,
) {
    private val log = LoggerFactory.getLogger(SignalQueryService::class.java)

    /**
     * 사용자 시계열 시그널 페이징 조회 — code·interval·side·strategyId 옵션 필터.
     *
     * @param userId 사용자 ID
     * @param from 시작 시각 (UTC Instant)
     * @param to 종료 시각 (UTC Instant)
     * @param code 종목 코드 부분 검색 (null=전체)
     * @param interval 평가 인터벌 (null=전체)
     * @param side 매매 방향 BUY/SELL/HOLD (null=전체)
     * @param strategyId 발행 전략 (null=전체)
     * @param page 페이지 번호 (0-based)
     * @param size 페이지 크기
     * @return enrich된 시그널 페이지 (ts DESC)
     */
    @Transactional(readOnly = true)
    fun list(
        userId: String,
        from: Instant,
        to: Instant,
        code: String?,
        interval: String?,
        side: String?,
        strategyId: Long?,
        page: Int,
        size: Int,
    ): Page<EnrichedSignal> {
        log.debug(
            "시그널 목록 조회: userId={} from={} to={} code={} interval={} side={} strategyId={} page={} size={}",
            userId,
            from,
            to,
            code,
            interval,
            side,
            strategyId,
            page,
            size,
        )
        val signalPage = signalJpaAdapter.findByUserAndRange(userId, from, to, code, interval, side, strategyId, page, size)
        val strategyMap = loadStrategies(signalPage.content.map { it.strategyId })
        val instrumentNameMap = loadInstrumentNames(signalPage.content.map { it.code })
        return signalPage.map { enrich(it, strategyMap, instrumentNameMap) }
    }

    /**
     * 시그널 단건 조회.
     *
     * @param userId 소유자 검증용
     * @param id 시그널 ID
     * @return enrich된 시그널 (userId 불일치 시 404)
     */
    @Transactional(readOnly = true)
    fun get(
        userId: String,
        id: Long,
    ): EnrichedSignal {
        val signal =
            signalJpaAdapter.findById(id)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "시그널 없음: id=$id")
        if (signal.userId != userId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "시그널 없음: id=$id")
        }
        return enrich(
            signal,
            loadStrategies(listOf(signal.strategyId)),
            loadInstrumentNames(listOf(signal.code)),
        )
    }

    // --- private helper ---

    private fun loadStrategies(strategyIds: List<Long>): Map<Long, StrategyJpaEntity> {
        val distinct = strategyIds.distinct()
        if (distinct.isEmpty()) return emptyMap()
        return strategyJpaRepository.findAllById(distinct).mapNotNull { e -> e.id?.let { it to e } }.toMap()
    }

    private fun loadInstrumentNames(codes: List<String>): Map<String, String> {
        val distinct = codes.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty()) return emptyMap()
        return instrumentJpaRepository
            .findAllById(distinct)
            .asSequence()
            .filter { it.market != "UNKNOWN" }
            .filter { it.name.isNotBlank() && it.name != it.code }
            .associate { it.code to it.name }
    }

    private fun enrich(
        signal: Signal,
        strategyMap: Map<Long, StrategyJpaEntity>,
        instrumentNameMap: Map<String, String>,
    ): EnrichedSignal {
        val strategy = strategyMap[signal.strategyId]
        return EnrichedSignal(
            signal = signal,
            codeName = instrumentNameMap[signal.code],
            strategyName = strategy?.name,
            strategyNameKo = strategy?.nameKo,
            buyCondition = strategy?.dslEntry,
            sellCondition = strategy?.dslExit,
        )
    }
}
