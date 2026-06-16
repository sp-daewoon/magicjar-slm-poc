package kr.spdaewoon.magicjar.consumer.order

import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.runtime.RuntimeModeParser
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * 외부(KIS HTS·MTS) 주문 흡수 시 KIS 계좌 식별자 → 내부 userId·TradingMode 매핑 (2026-06-05 자본 path fix).
 *
 * ### 배경 (라이브 실측 2026-06-05)
 * KIS WS 체결통보(H0STCNI9 모의) `CUST_ID`(HTS 사용자 ID, 예: "thdeodns")를 내부 userId로 그대로 사용 →
 * `user_account`(74683223)에 없는 ID → position upsert FK 위반 → 실시간 반영 실패 + reconcile 알림 2회.
 * 또한 stub `mode`를 무조건 REAL 로 박아 모의 체결인데 REAL 기록 → ADR 0109 canary cap 오염.
 *
 * ### 매핑 경로 (하드코딩 금지 — DB 권위)
 * KIS WS 체결통보 `ACNT_NO`(계좌번호) = `user_kis_credential.account_number`(CANO) 와 정합.
 * 따라서 계좌번호로 내부 userId·account_type 을 역조회한다 (HTS custId 는 매핑 키가 아님 — 단순 audit 표시용).
 *
 * 단일 사용자 현행이지만 매핑 테이블(`user_kis_credential`) 기반 — 다중 사용자 확장 시 그대로 동작.
 *
 * ### mode 정합
 * 매핑된 자격증명 row의 `account_type`(REAL/MOCK)을 TradingMode로 환산 (체결통보 trId 파싱보다 robust).
 * 한 계좌번호가 REAL·MOCK 양쪽에 있으면 runtime `magicjar.mode` 와 일치하는 쪽을 우선 (ambiguous WARN).
 *
 * ### 실패 시 정직 처리 (FK 폭발 회피)
 * 매핑 실패 시 stub 생성을 거부하지 않고 [Resolution.Unmapped] 반환 → caller 가 보류 마커 userId +
 * WARN 으로 정직 기록 (현행 FK 위반으로 죽는 것보다 audit 가능한 실패가 자본 안전 우선).
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0068 §8 + 2026-06-05 외부 주문 흡수 fix)
 */
@Component
class ExternalOrderAccountResolver(
    private val credentialRepository: ConsumerUserKisCredentialJpaRepository,
    @Value("\${magicjar.mode:MOCK}")
    private val magicjarMode: String,
) {
    private val log = LoggerFactory.getLogger(ExternalOrderAccountResolver::class.java)

    companion object {
        /**
         * 매핑 실패한 외부 흡수 주문의 보류 userId 마커.
         *
         * FK 위반으로 죽는 대신 audit 가능한 정직한 실패 — 운영자가 본 마커로 미매핑 주문을 즉시 식별.
         * `user_account` 에 실제 row 가 없으면 stub INSERT 자체가 FK 위반이므로, caller 는 본 마커 사용 전
         * [Resolution.Unmapped] 를 받아 SystemEvent WARN 후 보류(skip 또는 마커 INSERT 정책)를 결정한다.
         */
        const val UNMAPPED_USER_MARKER: String = "EXTERNAL_UNMAPPED"
    }

    /**
     * KIS 계좌번호(ACNT_NO) + HTS custId(audit용) → 내부 매핑 결과.
     *
     * @param accountNo KIS WS ACNT_NO (계좌번호 — CANO 또는 CANO-PRDT 포맷 가능)
     * @param htsId     KIS WS CUST_ID (HTS 사용자 ID — audit 표시용, 매핑 키 아님)
     */
    fun resolve(
        accountNo: String,
        htsId: String,
    ): Resolution {
        val cano = normalizeCano(accountNo)
        if (cano.isBlank()) {
            log.warn("ExternalOrderAccountResolver — 계좌번호 공백, 매핑 불가: htsId={}", maskUserId(htsId))
            return Resolution.Unmapped(htsId = htsId, accountNo = accountNo, reason = "BLANK_ACCOUNT_NO")
        }

        val creds =
            try {
                credentialRepository.findAllByAccountNumber(cano)
            } catch (e: Exception) {
                log.error(
                    "ExternalOrderAccountResolver — credential 조회 실패: cano={} htsId={} err={}",
                    cano,
                    maskUserId(htsId),
                    e.message,
                )
                return Resolution.Unmapped(
                    htsId = htsId,
                    accountNo = accountNo,
                    reason = "CREDENTIAL_LOOKUP_FAILED:${e.message ?: "UNKNOWN"}",
                )
            }

        if (creds.isEmpty()) {
            log.warn(
                "ExternalOrderAccountResolver — 매핑되는 내부 사용자 없음 (FK 위반 회피 위해 Unmapped 반환): cano={} htsId={}",
                cano,
                maskUserId(htsId),
            )
            return Resolution.Unmapped(htsId = htsId, accountNo = accountNo, reason = "ACCOUNT_NOT_REGISTERED")
        }

        // 다중 매칭(REAL+MOCK 동일 계좌번호) 시 runtime mode 우선, 그래도 ambiguous면 첫 row + WARN.
        val runtimeAccountType = RuntimeModeParser.accountTypeOrMock(magicjarMode)
        val chosen =
            creds.firstOrNull { AccountType.valueOf(it.id.accountType) == runtimeAccountType }
                ?: creds.first()
        if (creds.size > 1) {
            log.warn(
                "ExternalOrderAccountResolver — 동일 계좌번호 다중 자격증명 {}건, runtime({}) 우선 선택: cano={} chosen={}",
                creds.size,
                runtimeAccountType.name,
                cano,
                chosen.id.accountType,
            )
        }

        val accountType = AccountType.valueOf(chosen.id.accountType)
        val mode = if (accountType == AccountType.REAL) TradingMode.REAL else TradingMode.MOCK
        log.info(
            "ExternalOrderAccountResolver — 매핑 성공: cano={} htsId={} -> userId={} accountType={} mode={}",
            cano,
            maskUserId(htsId),
            maskUserId(chosen.id.userId),
            accountType.name,
            mode.name,
        )
        return Resolution.Mapped(
            userId = chosen.id.userId,
            accountType = accountType,
            mode = mode,
            htsId = htsId,
        )
    }

    /**
     * KIS 계좌번호 정규화 — `CANO-ACNT_PRDT_CD`(예: "74683223-01") 또는 공백 포함 가능 → 앞 8자리 CANO 추출.
     *
     * - `-` 구분자 있으면 앞부분만
     * - 8자리 초과 숫자면 앞 8자리 (KIS CANO 8자리 규약)
     * - 그 외는 trim 그대로 (방어적 — 매칭 실패 시 Unmapped 처리되므로 안전)
     */
    internal fun normalizeCano(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val head = trimmed.substringBefore('-').trim()
        return if (head.length > 8 && head.all { it.isDigit() }) head.take(8) else head
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"

    /**
     * 외부 주문 흡수 매핑 결과 — 진보 페르소나 sealed interface 상태 표현 (ADR 0087).
     *
     * caller 는 when 분기에서 컴파일 타임 exhaustive 안전성을 확보한다.
     */
    sealed interface Resolution {
        /** 매핑 성공 — 내부 userId·accountType·TradingMode 확정. */
        data class Mapped(
            val userId: String,
            val accountType: AccountType,
            val mode: TradingMode,
            val htsId: String,
        ) : Resolution

        /** 매핑 실패 — FK 폭발 대신 정직한 실패 (caller 가 SystemEvent WARN + 보류 결정). */
        data class Unmapped(
            val htsId: String,
            val accountNo: String,
            val reason: String,
        ) : Resolution
    }
}
