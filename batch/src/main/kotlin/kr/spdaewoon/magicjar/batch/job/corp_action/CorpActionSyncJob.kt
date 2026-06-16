package kr.spdaewoon.magicjar.batch.job.corpaction

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.instrument.corpaction.CorpAction
import kr.spdaewoon.magicjar.domain.instrument.corpaction.port.CorpActionRepository
import kr.spdaewoon.magicjar.domain.instrument.corpaction.port.DartDisclosureFetcher
import kr.spdaewoon.magicjar.domain.instrument.corpaction.port.KisKsdInfoFetcher
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * 기업 행위(Corporate Action) sync 잡 (T-CA1 PR-29b — ADR 0018 §2-5 정합).
 *
 * 실행 주기: cron 18:30 KST (KRX 마감 + 정리 시간 후)
 *   → magicjar.corp-action.sync-cron 환경변수 오버라이드 가능
 *
 * 처리 흐름 (ADR 0018 §4-6):
 *  1. KIS ksdinfo 6 endpoint 순차 호출 (Rate Limiter 통과 — ADR 0037)
 *     fetchDividend / fetchRevSplit / fetchMergerSplit / fetchBonusIssue
 *     / fetchPaidinCapin / fetchCapDcrs → corp_action upsert
 *  2. DART list.json 호출 (pblntf_ty=B+D, 어제~오늘)
 *     → corp_action upsert (source=DART — KIS 중복 시 별도 row 보존)
 *  3. pending corp_action (appliedAt=null) → Kafka publish corp_action.applied.v1
 *     → CandleAdjustmentJob 트리거
 *
 * KIS ksdinfo 6 endpoint 출처 (헌법 §3 — 자체 추론 0):
 *  - HHKDB669100C0: /uapi/domestic-stock/v1/ksdinfo/paidin-capin
 *  - HHKDB669101C0: /uapi/domestic-stock/v1/ksdinfo/bonus-issue
 *  - HHKDB669102C0: /uapi/domestic-stock/v1/ksdinfo/dividend
 *  - HHKDB669104C0: /uapi/domestic-stock/v1/ksdinfo/merger-split
 *  - HHKDB669105C0: /uapi/domestic-stock/v1/ksdinfo/rev-split
 *  - HHKDB669106C0: /uapi/domestic-stock/v1/ksdinfo/cap-dcrs
 *
 * DART OPENDART 출처:
 *  - https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019001
 *  - endpoint: https://opendart.fss.or.kr/api/list.json
 *
 * Kafka topic: corp_action.applied.v1 (PR-29b KafkaTopicConfig 단독 add)
 */
@Component
class CorpActionSyncJob(
    private val kisKsdInfoFetcher: KisKsdInfoFetcher,
    private val dartDisclosureFetcher: DartDisclosureFetcher,
    private val corpActionRepository: CorpActionRepository,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(CorpActionSyncJob::class.java)

    // 18:30 KST (UTC+9) = 09:30 UTC
    @Scheduled(cron = "\${magicjar.corp-action.sync-cron:0 30 9 * * *}")
    fun run() {
        log.info("[CorpActionSync] 시작 — cron 18:30 KST")
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)

        var upserted = 0
        var errors = 0

        // 1. KIS ksdinfo 6 endpoint — 최근 30일 범위 (신규 corp_action 발생 시 upsert)
        val kisWindow = today.minusDays(30)
        val kisActions = mutableListOf<CorpAction>()

        runCatching {
            kisActions += kisKsdInfoFetcher.fetchPaidinCapin(null, kisWindow, today)
        }.onFailure {
            log.error("[CorpActionSync] fetchPaidinCapin 실패 ${it.message}")
            errors++
        }

        runCatching {
            kisActions += kisKsdInfoFetcher.fetchBonusIssue(null, kisWindow, today)
        }.onFailure {
            log.error("[CorpActionSync] fetchBonusIssue 실패 ${it.message}")
            errors++
        }

        runCatching {
            kisActions += kisKsdInfoFetcher.fetchDividend(null, kisWindow, today)
        }.onFailure {
            log.error("[CorpActionSync] fetchDividend 실패 ${it.message}")
            errors++
        }

        runCatching {
            kisActions += kisKsdInfoFetcher.fetchMergerSplit(null, kisWindow, today)
        }.onFailure {
            log.error("[CorpActionSync] fetchMergerSplit 실패 ${it.message}")
            errors++
        }

        runCatching {
            kisActions += kisKsdInfoFetcher.fetchRevSplit(null, kisWindow, today)
        }.onFailure {
            log.error("[CorpActionSync] fetchRevSplit 실패 ${it.message}")
            errors++
        }

        runCatching {
            kisActions += kisKsdInfoFetcher.fetchCapDcrs(null, kisWindow, today)
        }.onFailure {
            log.error("[CorpActionSync] fetchCapDcrs 실패 ${it.message}")
            errors++
        }

        for (action in kisActions) {
            runCatching { corpActionRepository.upsert(action) }
                .onSuccess { upserted++ }
                .onFailure { log.warn("[CorpActionSync] KIS upsert 실패 code=${action.instrumentCode} type=${action.type} ${it.message}") }
        }

        // 2. DART list.json — 어제~오늘 (pblntf_ty=B+D)
        val dartActions =
            runCatching {
                dartDisclosureFetcher.fetchRecent(yesterday, today)
            }.onFailure {
                log.warn("[CorpActionSync] DART fetchRecent 실패 ${it.message}")
            }.getOrElse { emptyList() }

        for (action in dartActions) {
            runCatching { corpActionRepository.upsert(action) }
                .onSuccess { upserted++ }
                .onFailure { log.warn("[CorpActionSync] DART upsert 실패 code=${action.instrumentCode} type=${action.type} ${it.message}") }
        }

        // 3. pending corp_action → Kafka publish → CandleAdjustmentJob 트리거
        val pending = runCatching { corpActionRepository.findPendingApplied() }.getOrElse { emptyList() }
        var published = 0
        for (action in pending) {
            runCatching {
                val payload =
                    objectMapper.writeValueAsString(
                        mapOf(
                            "id" to action.id,
                            "instrumentCode" to action.instrumentCode,
                            "type" to action.type.name,
                            "exDate" to action.exDate.toString(),
                            "ratio" to action.ratio,
                            "cashDividend" to action.cashDividend,
                            "source" to action.source,
                        ),
                    )
                // Kafka topic: corp_action.applied.v1 (PR-29b KafkaTopicConfig 단독 add)
                kafkaTemplate.send(KafkaTopics.Data.CORP_ACTION_APPLIED, action.instrumentCode, payload)
                published++
            }.onFailure { log.error("[CorpActionSync] Kafka publish 실패 id=${action.id} ${it.message}") }
        }

        log.info(
            "[CorpActionSync] 완료 — KIS=${kisActions.size}건 DART=${dartActions.size}건 " +
                "upserted=$upserted published=$published errors=$errors",
        )
    }
}
