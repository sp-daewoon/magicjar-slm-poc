package kr.spdaewoon.magicjar.domain.instrument.corpaction.port

import kr.spdaewoon.magicjar.domain.instrument.corpaction.CorpAction
import java.time.LocalDate

/**
 * DART OPENDART 공시 조회 port (ADR 0018 §2-5 + external-api-policy.md §4 정합).
 *
 * DART OPENDART list.json 출처:
 *   https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019001
 *   endpoint: https://opendart.fss.or.kr/api/list.json
 *   pblntf_ty=B (주요사항보고서) — 액면분할·합병·증자 결정
 *   pblntf_ty=D (지분공시)       — 배당 결정
 *   한도: 1일 10,000건 (external-api-policy.md §4-1)
 *
 * 구현: api 모듈 DartDisclosureAdapter
 */
interface DartDisclosureFetcher {
    /**
     * 종목 코드에 해당하는 DART 공시 목록 조회 (corp_action 후보 파싱).
     * DART corp_code ↔ stock_code 매핑은 어댑터 내 CORPCODE.xml in-memory cache 담당.
     * 본문 상세(document.xml) fetch는 비범위 — list만 (ADR 0018 §4-7 정합).
     */
    fun fetchByCode(
        code: String,
        from: LocalDate,
        to: LocalDate,
    ): List<CorpAction>

    /**
     * 전체 종목 기준 기간 공시 조회 (CorpActionSyncJob 배치 전용).
     * pblntf_ty=B+D 동시 조회, 페이징 자동 처리.
     */
    fun fetchRecent(
        from: LocalDate,
        to: LocalDate,
    ): List<CorpAction>
}
