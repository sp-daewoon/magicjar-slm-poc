package kr.spdaewoon.magicjar.batch.infrastructure.kis

import kotlinx.datetime.LocalDate
import kr.spdaewoon.magicjar.domain.instrument.Instrument
import kr.spdaewoon.magicjar.domain.instrument.InstrumentMarket
import kr.spdaewoon.magicjar.domain.instrument.InstrumentType
import kr.spdaewoon.magicjar.domain.instrument.port.KisInstrumentMasterPort
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisErrorClass
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.market.ratelimit.classifyKisError
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.math.BigDecimal
import java.nio.charset.Charset
import java.time.Duration
import java.util.zip.ZipInputStream

/**
 * KIS 종목 마스터 fetch 어댑터 (PR-21a 본격 보강 — ADR 0017·0039 정합).
 *
 * KIS open-trading-api raw 검증 정합:
 *   raw 검증 출처: _workspace/specs/pr-21-kis-header-raw-verification.md
 *   KOSPI: https://github.com/koreainvestment/open-trading-api/blob/main/stocks_info/종목마스터정보(코스피).h
 *   KOSDAQ: https://github.com/koreainvestment/open-trading-api/blob/main/stocks_info/종목마스터정보(코스닥).h
 *   Python: https://github.com/koreainvestment/open-trading-api/blob/main/stocks_info/kis_kospi_code_mst.py
 *           https://github.com/koreainvestment/open-trading-api/blob/main/stocks_info/kis_kosdaq_code_mst.py
 *
 * 다운로드 URL (KIS-first — 자체 추론 0건):
 *   KOSPI: https://new.real.download.dws.co.kr/common/master/kospi_code.mst.zip
 *           (kis_kospi_code_mst.py L17 직접 인용)
 *   KOSDAQ: https://new.real.download.dws.co.kr/common/master/kosdaq_code.mst.zip
 *           (kis_kosdaq_code_mst.py L17 직접 인용)
 *
 * 파일 형식: fixed-width text, cp949(EUC-KR) 인코딩.
 * PR-20 byte-단위 정정 유지 (한글명 EUC-KR 2byte/char).
 *
 * 실패 처리 (ADR 0039 §2-4):
 *   5xx → exponential backoff 3회 (1s/2s/4s)
 *   네트워크 오류 → backoff 동일
 *   실패 지속 시 throw → 호출자(InstrumentSyncJob)가 catch + WARN 로그 + 시드 fallback 유지
 */
@Component
class KisInstrumentMasterAdapter(
    private val webClient: WebClient,
    private val kisRateLimiterPort: KisRateLimiterPort,
    @Value("\${magicjar.instrument.kospi-mst-url:https://new.real.download.dws.co.kr/common/master/kospi_code.mst.zip}")
    private val kospiMstUrl: String,
    @Value("\${magicjar.instrument.kosdaq-mst-url:https://new.real.download.dws.co.kr/common/master/kosdaq_code.mst.zip}")
    private val kosdaqMstUrl: String,
    @Value("\${magicjar.instrument.kis-disabled:false}")
    private val kisDisabled: Boolean,
) : KisInstrumentMasterPort {
    private val log = LoggerFactory.getLogger(KisInstrumentMasterAdapter::class.java)

    companion object {
        private val EUC_KR = Charset.forName("EUC-KR")
        private val BACKOFF_DELAYS = listOf(1000L, 2000L, 4000L)

        // =============================================================================
        // KOSPI Part2 widths — 70 필드, sum=227byte
        // raw 검증 출처: _workspace/specs/pr-21-kis-header-raw-verification.md §2-2
        // Python kis_kospi_code_mst.py L54~L68 + 종목마스터정보(코스피).h L9~L98 정합 (mismatch 0건)
        // =============================================================================
        private val KOSPI_PART2_WIDTHS =
            intArrayOf(
                // #1~5: scrt_grp_cls(2) avls_scal(1) bstp_larg(4) bstp_medm(4) bstp_smal(4)
                2,
                1,
                4,
                4,
                4,
                // #6~10: mnin_cls(1) low_current(1) sprn_strr(1) kospi200_apnt(1) kospi100(1)
                1,
                1,
                1,
                1,
                1,
                // #11~15: kospi50(1) krx(1) etp_prod(1) elw_pblc(1) krx100(1)
                1,
                1,
                1,
                1,
                1,
                // #16~20: krx_car(1) krx_smcn(1) krx_bio(1) krx_bank(1) etpr_undt_SPAC(1)
                1,
                1,
                1,
                1,
                1,
                // #21~25: krx_enrg_chms(1) krx_stel(1) short_over(1) krx_medi_cmnc(1) krx_cnst(1)
                1,
                1,
                1,
                1,
                1,
                // #26~30: krx_fnnc_svc_dummy(1) krx_scrt(1) krx_ship(1) krx_insu(1) krx_trnp(1)
                1,
                1,
                1,
                1,
                1,
                // #31~35: sri_nmix(1) stck_sdpr(9) frml_mrkt_deal(5) ovtm_mrkt_deal(5) trht(1)
                1,
                9,
                5,
                5,
                1,
                // #36~40: sltr(1) mang(1) mrkt_alrm(2) mrkt_alrm_risk_adnt(1) insn_pbnt(1)
                1,
                1,
                2,
                1,
                1,
                // #41~45: byps_lstn(1) flng(2) fcam_mod(2) icic(2) marg_rate(3)
                1,
                2,
                2,
                2,
                3,
                // #46~50: crdt_able(1) crdt_days(3) prdy_vol(12) stck_fcam(12) stck_lstn_date(8)
                1,
                3,
                12,
                12,
                8,
                // #51~55: lstn_stcn(15) cpfn(21) stac_month(2) po_prc(7) prst_cls(1)
                15,
                21,
                2,
                7,
                1,
                // #56~60: ssts_hot(1) stange_runup(1) krx300(1) kospi_issu(1) sale_account(9)
                1,
                1,
                1,
                1,
                9,
                // #61~65: bsop_prfi(9) op_prfi(9) thtr_ntin(5) roe(9) base_date(8)
                9,
                9,
                5,
                9,
                8,
                // #66~70: prdy_avls_scal(9) grp_code(3) co_crdt_limt_over(1) secu_lend_able(1) stln_able(1)
                9,
                3,
                1,
                1,
                1,
            )

        // =============================================================================
        // KOSDAQ Part2 widths — 64 필드, sum=221byte
        // raw 검증 출처: _workspace/specs/pr-21-kis-header-raw-verification.md §3-2
        // Python kis_kosdaq_code_mst.py L54~L67 + 종목마스터정보(코스닥).h L9~L88 정합 (mismatch 0건)
        // KOSPI 대비 차이: mnin·sprn·kospi200_apnt·kospi100·kospi50·elw_pblc·krx_fnnc_svc·sri_nmix·kospi_issu 부재(9필드)
        //                  vntr_issu·invt_alrm·ksq150_nmix 추가(3필드)
        // =============================================================================
        private val KOSDAQ_PART2_WIDTHS =
            intArrayOf(
                // #1~2: scrt_grp_cls(2) avls_scal(1)
                2,
                1,
                // #3~7: bstp_larg(4) bstp_medm(4) bstp_smal(4) vntr_issu(1) low_current(1)
                4,
                4,
                4,
                1,
                1,
                // #8~12: krx(1) etp_prod(1) krx100(1) krx_car(1) krx_smcn(1)
                1,
                1,
                1,
                1,
                1,
                // #13~17: krx_bio(1) krx_bank(1) etpr_undt_SPAC(1) krx_enrg_chms(1) krx_stel(1)
                1,
                1,
                1,
                1,
                1,
                // #18~22: short_over(1) krx_medi_cmnc(1) krx_cnst(1) invt_alrm(1) krx_scrt(1)
                1,
                1,
                1,
                1,
                1,
                // #23~27: krx_ship(1) krx_insu(1) krx_trnp(1) ksq150_nmix(1) stck_sdpr(9)
                1,
                1,
                1,
                1,
                9,
                // #28~32: frml_mrkt_deal(5) ovtm_mrkt_deal(5) trht(1) sltr(1) mang(1)
                5,
                5,
                1,
                1,
                1,
                // #33~37: mrkt_alrm(2) mrkt_alrm_risk_adnt(1) insn_pbnt(1) byps_lstn(1) flng(2)
                2,
                1,
                1,
                1,
                2,
                // #38~42: fcam_mod(2) icic(2) marg_rate(3) crdt_able(1) crdt_days(3)
                2,
                2,
                3,
                1,
                3,
                // #43~47: prdy_vol(12) stck_fcam(12) stck_lstn_date(8) lstn_stcn(15) cpfn(21)
                12,
                12,
                8,
                15,
                21,
                // #48~52: stac_month(2) po_prc(7) prst_cls(1) ssts_hot(1) stange_runup(1)
                2,
                7,
                1,
                1,
                1,
                // #53~57: krx300(1) sale_account(9) bsop_prfi(9) op_prfi(9) thtr_ntin(5)
                1,
                9,
                9,
                9,
                5,
                // #58~62: roe(9) base_date(8) prdy_avls_scal(9) grp_code(3) co_crdt_limt_over(1)
                9,
                8,
                9,
                3,
                1,
                // #63~64: secu_lend_able(1) stln_able(1)
                1,
                1,
            )

        private const val KOSPI_PART2_LENGTH = 227 // KOSPI_PART2_WIDTHS.sum() — 줄바꿈 1byte 미포함
        private const val KOSDAQ_PART2_LENGTH = 221 // KOSDAQ_PART2_WIDTHS.sum() — 줄바꿈 1byte 미포함

        // ROE NUMERIC(7,2) 한계 = 절댓값 < 100000.00.
        // KIS는 ROE 산정 불가 종목(적자/자본잠식/신생/소수점 누락)에 sentinel 값(-36100000 등 9자리 숫자)을 채워서 보냄.
        // 정상 raw는 "000010.85" 형식(소수점 포함, 절댓값 99999.99 이내).
        // overflow가 들어가면 row 전체 upsert가 rollback되므로 어댑터에서 sentinel → null + warn.
        private val ROE_DB_LIMIT = BigDecimal("99999.99")

        // KIS 알려진 sentinel 값 — "산정 불가/데이터 없음" 표식.
        // 2026-05 기준 raw 검증: ROE 16건(KOSPI 3 + KOSDAQ 13)에서만 발견.
        // 다른 재무 필드(sale_account/bsop_prfi/op_prfi/thtr_ntin/prdy_avls_scal)에는 현재 미사용이지만
        // 사용자 요청에 따라 동일 패턴으로 안전망 적용 (향후 KIS가 다른 필드에 같은 sentinel 채워도 무방).
        private const val KIS_SENTINEL_INVALID = "-36100000"
    }

    /**
     * KIS 종목 마스터 전체 fetch — KOSPI + KOSDAQ.
     * 반환: 도메인 Instrument list (기존 port 계약 유지 — 호출자 InstrumentSyncJob 보강 대상).
     */
    override fun fetchAll(): List<Instrument> {
        if (kisDisabled) {
            log.info("[instrument-master] KIS disabled (magicjar.instrument.kis-disabled=true) — fetch skip")
            return emptyList()
        }
        log.info("[instrument-master] KIS 종목 마스터 fetch 시작 (PR-21a 본격 파싱)")
        val instruments = mutableListOf<Instrument>()
        instruments.addAll(fetchRawMarket(kospiMstUrl, InstrumentMarket.KOSPI, "KOSPI").map { it.toDomain() })
        instruments.addAll(fetchRawMarket(kosdaqMstUrl, InstrumentMarket.KOSDAQ, "KOSDAQ").map { it.toDomain() })
        log.info("[instrument-master] fetch 완료 — total={}", instruments.size)
        return instruments
    }

    /**
     * KIS 종목 마스터 KisInstrumentRaw fetch — 70+ 필드 파싱 결과 제공.
     * InstrumentSyncJob이 이 메서드를 통해 전체 필드 upsert.
     */
    fun fetchAllRaw(): List<KisInstrumentRaw> {
        if (kisDisabled) {
            log.info("[instrument-master] KIS disabled — raw fetch skip")
            return emptyList()
        }
        log.info("[instrument-master] KIS 종목 마스터 raw fetch 시작")
        val raws = mutableListOf<KisInstrumentRaw>()
        raws.addAll(fetchRawMarket(kospiMstUrl, InstrumentMarket.KOSPI, "KOSPI"))
        raws.addAll(fetchRawMarket(kosdaqMstUrl, InstrumentMarket.KOSDAQ, "KOSDAQ"))
        log.info("[instrument-master] raw fetch 완료 — total={}", raws.size)
        return raws
    }

    private fun fetchRawMarket(
        url: String,
        market: InstrumentMarket,
        marketName: String,
    ): List<KisInstrumentRaw> {
        var lastException: Exception? = null
        for ((idx, delay) in BACKOFF_DELAYS.withIndex()) {
            try {
                val zipBytes = downloadWithTimeout(url)
                val raws = parseZipBytesRaw(zipBytes, market)
                log.info("[instrument-master] {} 파싱 완료 — {} 건", marketName, raws.size)
                return raws
            } catch (e: WebClientResponseException) {
                lastException = e
                if (e.statusCode.value() in 400..499) {
                    log.error(
                        "[instrument-master] {} 4xx 오류 — 즉시 실패: status={} body={}",
                        marketName,
                        e.statusCode,
                        e.responseBodyAsString.take(200),
                    )
                    throw e
                }
                log.warn(
                    "[instrument-master] {} 5xx 오류 (시도 {}/{}) — {}ms 후 재시도: status={}",
                    marketName,
                    idx + 1,
                    BACKOFF_DELAYS.size,
                    delay,
                    e.statusCode,
                )
            } catch (e: Exception) {
                lastException = e
                log.warn(
                    "[instrument-master] {} 네트워크 오류 (시도 {}/{}) — {}ms 후 재시도: {}",
                    marketName,
                    idx + 1,
                    BACKOFF_DELAYS.size,
                    delay,
                    e.message,
                )
            }
            if (idx < BACKOFF_DELAYS.size - 1) Thread.sleep(delay)
        }
        log.error("[instrument-master] {} fetch 3회 모두 실패 — 시드 fallback 유지됨", marketName)
        throw lastException ?: RuntimeException("$marketName 종목 마스터 fetch 실패")
    }

    private fun downloadWithTimeout(url: String): ByteArray {
        log.debug("[instrument-master] 다운로드: {}", url)
        // Rate Limit acquire (D-2 합류 — BACKFILL priority — 1일 1회 sync-at-boot + cron이라 risk 낮으나 burst 가능)
        // try 안 진입 + signalSent flag로 drift 차단 (D-1 패턴 정합).
        var signalSent = false
        return try {
            kisRateLimiterPort.acquire(KisCallPriority.BACKFILL, timeoutMillis = 5000L, trId = "instrument_master")
            val bytes =
                webClient
                    .get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(ByteArray::class.java)
                    .timeout(Duration.ofSeconds(30))
                    .block()
                    ?: error("종목 마스터 zip 다운로드 응답 없음: url=$url")
            kisRateLimiterPort.onSuccess()
            signalSent = true
            bytes
        } catch (e: WebClientResponseException) {
            // P0 hotfix (진단 PR #639) — classifyKisError() 분기 통일 (EGW00201 = HTTP 500 wrap).
            if (classifyKisError(e.statusCode.value(), e.responseBodyAsString) is KisErrorClass.RateLimit) {
                kisRateLimiterPort.onTpsExceeded()
                signalSent = true
            }
            throw e
        } finally {
            if (!signalSent) {
                log.warn(
                    "[KisRateLimit] instrument-master acquire 후 통계 시그널 미발행 — drift risk (caller 예외 추정): url={}",
                    url,
                )
            }
        }
    }

    private fun parseZipBytesRaw(
        zipBytes: ByteArray,
        market: InstrumentMarket,
    ): List<KisInstrumentRaw> {
        val raws = mutableListOf<KisInstrumentRaw>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val content = zis.readBytes()
                    val reader = BufferedReader(InputStreamReader(ByteArrayInputStream(content), EUC_KR))
                    reader.forEachLine { line ->
                        val raw = parseLineRaw(line, market)
                        if (raw != null) raws.add(raw)
                    }
                }
                entry = zis.nextEntry
            }
        }
        return raws
    }

    /**
     * 고정폭 레코드 한 줄 파싱 — 70+ 필드 KisInstrumentRaw 반환.
     *
     * KIS-first 출처: _workspace/specs/pr-21-kis-header-raw-verification.md §2·§3·§6
     * KOSPI Part2 widths (.h L9~L98 + kis_kospi_code_mst.py L54~L68 정합)
     * KOSDAQ Part2 widths (.h L9~L88 + kis_kosdaq_code_mst.py L54~L67 정합)
     *
     * 파싱 전략 (PR-20 byte-단위 정합 유지 + PR-21 Part2 본격 파싱):
     *   1. line → EUC-KR byte 배열 (PR-20 정정 유지)
     *   2. Part1: byte[0~8]=단축코드, byte[9~20]=ISIN, byte[21~(lineBytes.size-part2Length-1)]=한글명 가변
     *   3. Part2: KOSPI/KOSDAQ widths 배열로 substring (ASCII numeric → 1byte=1char 안전)
     *   4. 함정 §12-1 mismatch 방지: KOSPI_PART2_WIDTHS vs KOSDAQ_PART2_WIDTHS 분리
     *   5. 함정 §12-2 part1 가변: lineBytes.size - part2Length - 21
     *   6. 함정 §12-3 yyyymmdd "00000000": parseYyyymmdd() null 처리
     *   7. 함정 §12-4 roe 음수: toBigDecimalOrNull() 음수 부호 안전
     */
    private fun parseLineRaw(
        line: String,
        market: InstrumentMarket,
    ): KisInstrumentRaw? {
        val lineBytes = line.toByteArray(EUC_KR)
        val part2Length = if (market == InstrumentMarket.KOSPI) KOSPI_PART2_LENGTH else KOSDAQ_PART2_LENGTH

        // 최소 길이: Part1(9+12=21) + Part2 (한글명 최소 0byte 가정)
        if (lineBytes.size < 21 + part2Length) return null

        return try {
            // Part1: 단축코드(9) + ISIN(12) + 한글명(가변)
            val rawCode = String(lineBytes, 0, 9, EUC_KR).trim()
            val code = rawCode.take(6).trimStart('0').padStart(6, '0')
            if (code.length != 6 || !code.all { it.isDigit() }) return null

            val isin = String(lineBytes, 9, 12, EUC_KR).trim().ifBlank { null }

            // 함정 §12-2: 한글명 가변 — Part2 위치 기준 역산
            val nameByteLen = lineBytes.size - part2Length - 21
            if (nameByteLen < 0) return null
            val name = String(lineBytes, 21, nameByteLen, EUC_KR).trim()
            if (name.isBlank()) return null

            // Part2: widths 배열로 필드 분리
            val part2StartByte = 21 + nameByteLen
            val widths = if (market == InstrumentMarket.KOSPI) KOSPI_PART2_WIDTHS else KOSDAQ_PART2_WIDTHS
            val p = mutableListOf<String>()
            var offset = part2StartByte
            for (w in widths) {
                if (offset + w > lineBytes.size) {
                    p.add("")
                } else {
                    p.add(String(lineBytes, offset, w, EUC_KR).trim())
                }
                offset += w
            }

            if (market == InstrumentMarket.KOSPI) {
                parseKospiPart2(code, isin, name, p)
            } else {
                parseKosdaqPart2(code, isin, name, p)
            }
        } catch (e: Exception) {
            log.warn(
                "[instrument-master] 줄 파싱 실패 skip: market={} line='{}'... err={}",
                market,
                line.take(30),
                e.message,
            )
            null
        }
    }

    /**
     * KOSPI Part2 70 필드 매핑.
     * 인덱스 기준: KOSPI_PART2_WIDTHS 순서 (0-based).
     * raw 검증 출처: _workspace/specs/pr-21-kis-header-raw-verification.md §2-2 표
     */
    private fun parseKospiPart2(
        code: String,
        isin: String?,
        name: String,
        p: List<String>,
    ): KisInstrumentRaw {
        // p[0]=scrt_grp_cls(2) p[1]=avls_scal(1) p[2]=bstp_larg(4) p[3]=bstp_medm(4) p[4]=bstp_smal(4)
        // p[5]=mnin(1) p[6]=low_current(1) p[7]=sprn_strr(1) p[8]=kospi200_apnt(1) p[9]=kospi100(1)
        // p[10]=kospi50(1) p[11]=krx(1) p[12]=etp_prod(1) p[13]=elw_pblc(1) p[14]=krx100(1)
        // p[15]=krx_car(1) p[16]=krx_smcn(1) p[17]=krx_bio(1) p[18]=krx_bank(1) p[19]=SPAC(1)
        // p[20]=krx_enrg_chms(1) p[21]=krx_stel(1) p[22]=short_over(1) p[23]=krx_medi_cmnc(1) p[24]=krx_cnst(1)
        // p[25]=krx_fnnc_svc_dummy(1) p[26]=krx_scrt(1) p[27]=krx_ship(1) p[28]=krx_insu(1) p[29]=krx_trnp(1)
        // p[30]=sri_nmix(1) p[31]=stck_sdpr(9) p[32]=frml_mrkt_deal(5) p[33]=ovtm_mrkt_deal(5) p[34]=trht(1)
        // p[35]=sltr(1) p[36]=mang(1) p[37]=mrkt_alrm(2) p[38]=mrkt_alrm_risk_adnt(1) p[39]=insn_pbnt(1)
        // p[40]=byps_lstn(1) p[41]=flng(2) p[42]=fcam_mod(2) p[43]=icic(2) p[44]=marg_rate(3)
        // p[45]=crdt_able(1) p[46]=crdt_days(3) p[47]=prdy_vol(12) p[48]=stck_fcam(12) p[49]=stck_lstn_date(8)
        // p[50]=lstn_stcn(15) p[51]=cpfn(21) p[52]=stac_month(2) p[53]=po_prc(7) p[54]=prst_cls(1)
        // p[55]=ssts_hot(1) p[56]=stange_runup(1) p[57]=krx300(1) p[58]=kospi_issu(1) p[59]=sale_account(9)
        // p[60]=bsop_prfi(9) p[61]=op_prfi(9) p[62]=thtr_ntin(5) p[63]=roe(9) p[64]=base_date(8)
        // p[65]=prdy_avls_scal(9) p[66]=grp_code(3) p[67]=co_crdt_limt_over(1) p[68]=secu_lend_able(1) p[69]=stln_able(1)
        val scrtGrpCls = p.getOrEmpty(0)
        val prstCls = p.getOrEmpty(54)
        return KisInstrumentRaw(
            code = code,
            isin = isin,
            name = name,
            market = InstrumentMarket.KOSPI,
            type = mapType(scrtGrpCls, prstCls),
            securitiesGroup = scrtGrpCls.ifBlank { null },
            // Tier 2 매매 가드
            tradingHalt = p.getOrEmpty(34) == "Y",
            liquidation = p.getOrEmpty(35) == "Y",
            managementStock = p.getOrEmpty(36) == "Y",
            marketAlert = p.getOrEmpty(37).ifBlank { "00" },
            marketAlertWarning = p.getOrEmpty(38) == "Y",
            falseDisclosure = p.getOrEmpty(39) == "Y",
            backdoorListing = p.getOrEmpty(40) == "Y",
            shortOverheated = p.getOrEmpty(55) == "Y",
            abnormalRunup = p.getOrEmpty(56) == "Y",
            investmentCaution = false, // KOSPI에는 invt_alrm_yn 없음 — KOSDAQ 단독
            exRightsType = p.getOrEmpty(41).ifBlank { "00" },
            faceValueChange = p.getOrEmpty(42).ifBlank { "00" },
            capitalIncrease = p.getOrEmpty(43).ifBlank { "00" },
            creditAble = p.getOrEmpty(45) == "Y",
            creditDays = p.getOrEmpty(46).toShortOrNull(),
            marginRate = p.getOrEmpty(44).toShortOrNull(),
            shortOverheatedStatus = p.getOrEmpty(22).ifBlank { "0" },
            // Tier 3 분류
            avlsScale = p.getOrEmpty(1).ifBlank { null },
            industryLarge = p.getOrEmpty(2).ifBlank { null },
            industryMedium = p.getOrEmpty(3).ifBlank { null },
            industrySmall = p.getOrEmpty(4).ifBlank { null },
            kospi200Sector = p.getOrEmpty(8).ifBlank { null },
            isKospi100 = p.getOrEmpty(9) == "Y",
            isKospi50 = p.getOrEmpty(10) == "Y",
            isKospi200 = p.getOrEmpty(8).isNotBlank() && p.getOrEmpty(8) != "0", // derived
            isKosdaq150 = false, // KOSPI에 없음
            isKrx = p.getOrEmpty(11) == "Y",
            etpProductType = p.getOrEmpty(12).ifBlank { null },
            isElwPublisher = p.getOrEmpty(13) == "Y",
            isKrx100 = p.getOrEmpty(14) == "Y",
            krxSectorCar = p.getOrEmpty(15) == "Y",
            krxSectorSemicon = p.getOrEmpty(16) == "Y",
            krxSectorBio = p.getOrEmpty(17) == "Y",
            krxSectorBank = p.getOrEmpty(18) == "Y",
            isSpac = p.getOrEmpty(19) == "Y",
            krxSectorEnergyChem = p.getOrEmpty(20) == "Y",
            krxSectorSteel = p.getOrEmpty(21) == "Y",
            krxSectorMediaComm = p.getOrEmpty(23) == "Y",
            krxSectorConstruction = p.getOrEmpty(24) == "Y",
            // p[25] = krx_fnnc_svc dummy — 무시 (.h 주석: "삭제됨 20151218")
            krxSectorSecurities = p.getOrEmpty(26) == "Y",
            krxSectorShip = p.getOrEmpty(27) == "Y",
            krxSectorInsurance = p.getOrEmpty(28) == "Y",
            krxSectorTransport = p.getOrEmpty(29) == "Y",
            isSriIndex = p.getOrEmpty(30) == "Y",
            preferredStockType = prstCls.ifBlank { null },
            isKrx300 = p.getOrEmpty(57) == "Y",
            isManufacturing = p.getOrEmpty(5) == "Y",
            isLowLiquidity = p.getOrEmpty(6) == "Y",
            isGovernanceIndex = p.getOrEmpty(7) == "Y",
            isVenture = false, // KOSPI에 없음
            groupCode = p.getOrEmpty(66).ifBlank { null },
            // Tier 4 시장 메타 + 재무
            basePrice = p.getOrEmpty(31).toBigDecimalOrNull(),
            tradingUnitNormal = p.getOrEmpty(32).toIntOrNull(),
            tradingUnitOvertime = p.getOrEmpty(33).toIntOrNull(),
            prevVolume = p.getOrEmpty(47).toLongOrNull(),
            faceValue = p.getOrEmpty(48).toBigDecimalOrNull(),
            listedAt = parseYyyymmdd(p.getOrEmpty(49)), // stck_lstn_date
            listedShares = p.getOrEmpty(50).toLongOrNull(),
            capital = p.getOrEmpty(51).toBigDecimalOrNull(),
            settlementMonth = p.getOrEmpty(52).toShortOrNull(),
            publicOfferingPrice = p.getOrEmpty(53).toBigDecimalOrNull(),
            salesAmount = sanitizeFinancialLong(code, p.getOrEmpty(59), "sale_account"),
            operatingProfit = sanitizeFinancialLong(code, p.getOrEmpty(60), "bsop_prfi"),
            ordinaryProfit = sanitizeFinancialLong(code, p.getOrEmpty(61), "op_prfi"),
            netIncome = sanitizeFinancialLong(code, p.getOrEmpty(62), "thtr_ntin"),
            roe = sanitizeRoe(code, p.getOrEmpty(63)), // 함정 §12-4: 음수 가능 + KIS sentinel(-36100000 등) → null
            financialBaseDate = parseYyyymmdd(p.getOrEmpty(64)),
            marketCapPrev = sanitizeFinancialLong(code, p.getOrEmpty(65), "prdy_avls_scal"),
            creditLimitExceeded = p.getOrEmpty(67) == "Y",
            collateralLendable = p.getOrEmpty(68) == "Y",
            shortLendable = p.getOrEmpty(69) == "Y",
        )
    }

    /**
     * KOSDAQ Part2 64 필드 매핑.
     * 인덱스 기준: KOSDAQ_PART2_WIDTHS 순서 (0-based).
     * raw 검증 출처: _workspace/specs/pr-21-kis-header-raw-verification.md §3-2 표
     * KOSPI 대비 주요 차이:
     *   - mnin·sprn·kospi200_apnt·kospi100·kospi50·elw_pblc·krx_fnnc_svc·sri_nmix·kospi_issu 부재
     *   - vntr_issu(p[5]) + invt_alrm(p[21]) + ksq150_nmix(p[25]) 신규
     */
    private fun parseKosdaqPart2(
        code: String,
        isin: String?,
        name: String,
        p: List<String>,
    ): KisInstrumentRaw {
        // p[0]=scrt_grp_cls(2) p[1]=avls_scal(1) p[2]=bstp_larg(4) p[3]=bstp_medm(4) p[4]=bstp_smal(4)
        // p[5]=vntr_issu(1) p[6]=low_current(1) p[7]=krx(1) p[8]=etp_prod(1) p[9]=krx100(1)
        // p[10]=krx_car(1) p[11]=krx_smcn(1) p[12]=krx_bio(1) p[13]=krx_bank(1) p[14]=SPAC(1)
        // p[15]=krx_enrg_chms(1) p[16]=krx_stel(1) p[17]=short_over(1) p[18]=krx_medi_cmnc(1) p[19]=krx_cnst(1)
        // p[20]=invt_alrm(1) p[21]=krx_scrt(1) p[22]=krx_ship(1) p[23]=krx_insu(1) p[24]=krx_trnp(1)
        // p[25]=ksq150_nmix(1) p[26]=stck_sdpr(9) p[27]=frml_mrkt_deal(5) p[28]=ovtm_mrkt_deal(5) p[29]=trht(1)
        // p[30]=sltr(1) p[31]=mang(1) p[32]=mrkt_alrm(2) p[33]=mrkt_alrm_risk_adnt(1) p[34]=insn_pbnt(1)
        // p[35]=byps_lstn(1) p[36]=flng(2) p[37]=fcam_mod(2) p[38]=icic(2) p[39]=marg_rate(3)
        // p[40]=crdt_able(1) p[41]=crdt_days(3) p[42]=prdy_vol(12) p[43]=stck_fcam(12) p[44]=stck_lstn_date(8)
        // p[45]=lstn_stcn(15) p[46]=cpfn(21) p[47]=stac_month(2) p[48]=po_prc(7) p[49]=prst_cls(1)
        // p[50]=ssts_hot(1) p[51]=stange_runup(1) p[52]=krx300(1) p[53]=sale_account(9) p[54]=bsop_prfi(9)
        // p[55]=op_prfi(9) p[56]=thtr_ntin(5) p[57]=roe(9) p[58]=base_date(8) p[59]=prdy_avls_scal(9)
        // p[60]=grp_code(3) p[61]=co_crdt_limt_over(1) p[62]=secu_lend_able(1) p[63]=stln_able(1)
        val scrtGrpCls = p.getOrEmpty(0)
        val prstCls = p.getOrEmpty(49)
        return KisInstrumentRaw(
            code = code,
            isin = isin,
            name = name,
            market = InstrumentMarket.KOSDAQ,
            type = mapType(scrtGrpCls, prstCls),
            securitiesGroup = scrtGrpCls.ifBlank { null },
            // Tier 2 매매 가드
            tradingHalt = p.getOrEmpty(29) == "Y",
            liquidation = p.getOrEmpty(30) == "Y",
            managementStock = p.getOrEmpty(31) == "Y",
            marketAlert = p.getOrEmpty(32).ifBlank { "00" },
            marketAlertWarning = p.getOrEmpty(33) == "Y",
            falseDisclosure = p.getOrEmpty(34) == "Y",
            backdoorListing = p.getOrEmpty(35) == "Y",
            shortOverheated = p.getOrEmpty(50) == "Y",
            abnormalRunup = p.getOrEmpty(51) == "Y",
            investmentCaution = p.getOrEmpty(20) == "Y", // KOSDAQ 단독
            exRightsType = p.getOrEmpty(36).ifBlank { "00" },
            faceValueChange = p.getOrEmpty(37).ifBlank { "00" },
            capitalIncrease = p.getOrEmpty(38).ifBlank { "00" },
            creditAble = p.getOrEmpty(40) == "Y",
            creditDays = p.getOrEmpty(41).toShortOrNull(),
            marginRate = p.getOrEmpty(39).toShortOrNull(),
            shortOverheatedStatus = p.getOrEmpty(17).ifBlank { "0" },
            // Tier 3 분류
            avlsScale = p.getOrEmpty(1).ifBlank { null },
            industryLarge = p.getOrEmpty(2).ifBlank { null },
            industryMedium = p.getOrEmpty(3).ifBlank { null },
            industrySmall = p.getOrEmpty(4).ifBlank { null },
            kospi200Sector = null, // KOSPI만
            isKospi100 = false,
            isKospi50 = false,
            isKospi200 = false,
            isKosdaq150 = p.getOrEmpty(25) == "Y",
            isKrx = p.getOrEmpty(7) == "Y",
            etpProductType = p.getOrEmpty(8).ifBlank { null },
            isElwPublisher = false, // KOSPI만
            isKrx100 = p.getOrEmpty(9) == "Y",
            krxSectorCar = p.getOrEmpty(10) == "Y",
            krxSectorSemicon = p.getOrEmpty(11) == "Y",
            krxSectorBio = p.getOrEmpty(12) == "Y",
            krxSectorBank = p.getOrEmpty(13) == "Y",
            isSpac = p.getOrEmpty(14) == "Y",
            krxSectorEnergyChem = p.getOrEmpty(15) == "Y",
            krxSectorSteel = p.getOrEmpty(16) == "Y",
            krxSectorMediaComm = p.getOrEmpty(18) == "Y",
            krxSectorConstruction = p.getOrEmpty(19) == "Y",
            krxSectorSecurities = p.getOrEmpty(21) == "Y",
            krxSectorShip = p.getOrEmpty(22) == "Y",
            krxSectorInsurance = p.getOrEmpty(23) == "Y",
            krxSectorTransport = p.getOrEmpty(24) == "Y",
            isManufacturing = false, // KOSPI만
            isLowLiquidity = p.getOrEmpty(6) == "Y",
            isGovernanceIndex = false, // KOSPI만
            isVenture = p.getOrEmpty(5) == "Y", // KOSDAQ 단독
            isSriIndex = false, // KOSPI만
            preferredStockType = prstCls.ifBlank { null },
            isKrx300 = p.getOrEmpty(52) == "Y",
            groupCode = p.getOrEmpty(60).ifBlank { null },
            // Tier 4 시장 메타 + 재무
            basePrice = p.getOrEmpty(26).toBigDecimalOrNull(),
            tradingUnitNormal = p.getOrEmpty(27).toIntOrNull(),
            tradingUnitOvertime = p.getOrEmpty(28).toIntOrNull(),
            prevVolume = p.getOrEmpty(42).toLongOrNull(),
            faceValue = p.getOrEmpty(43).toBigDecimalOrNull(),
            listedAt = parseYyyymmdd(p.getOrEmpty(44)),
            listedShares = p.getOrEmpty(45).toLongOrNull(),
            capital = p.getOrEmpty(46).toBigDecimalOrNull(),
            settlementMonth = p.getOrEmpty(47).toShortOrNull(),
            publicOfferingPrice = p.getOrEmpty(48).toBigDecimalOrNull(),
            salesAmount = sanitizeFinancialLong(code, p.getOrEmpty(53), "sale_account"),
            operatingProfit = sanitizeFinancialLong(code, p.getOrEmpty(54), "bsop_prfi"),
            ordinaryProfit = sanitizeFinancialLong(code, p.getOrEmpty(55), "op_prfi"),
            netIncome = sanitizeFinancialLong(code, p.getOrEmpty(56), "thtr_ntin"),
            roe = sanitizeRoe(code, p.getOrEmpty(57)),
            financialBaseDate = parseYyyymmdd(p.getOrEmpty(58)),
            marketCapPrev = sanitizeFinancialLong(code, p.getOrEmpty(59), "prdy_avls_scal"),
            creditLimitExceeded = p.getOrEmpty(61) == "Y",
            collateralLendable = p.getOrEmpty(62) == "Y",
            shortLendable = p.getOrEmpty(63) == "Y",
        )
    }

    /**
     * scrt_grp_cls_code 권위 매핑 — detectType(name, code) 자체 추론 폐기 (PR-21a).
     * 우선주는 prst_cls_code 권위 사용 (코드 5번째 자리 휴리스틱 폐기).
     * ETN은 etp_prod_cls_code=3·4 권위 사용 (종목명 "ETN" substring 폐기).
     *
     * 출처: _workspace/specs/pr-21-kis-header-raw-verification.md §5 + .h L9~L14 정합
     */
    private fun mapType(
        scrtGrpClsCode: String,
        prstClsCode: String?,
    ): InstrumentType =
        when (scrtGrpClsCode) {
            "ST" -> if (prstClsCode == "1" || prstClsCode == "2") InstrumentType.PREFERRED else InstrumentType.STOCK
            "EF", "FE" -> InstrumentType.ETF
            "MF", "BC", "SC", "IF" -> InstrumentType.STOCK
            "RT" -> InstrumentType.REIT
            "DR" -> InstrumentType.DR
            "EW", "SW", "SR" -> InstrumentType.WARRANT
            "FS" -> InstrumentType.STOCK
            else -> InstrumentType.UNKNOWN
        }

    /**
     * yyyymmdd 문자열 → LocalDate 변환.
     * 함정 §12-3: "00000000" 또는 공백 → null 반환.
     */
    private fun parseYyyymmdd(s: String): java.time.LocalDate? {
        if (s.length != 8 || !s.all(Char::isDigit) || s == "00000000") return null
        return try {
            java.time.LocalDate.of(
                s.substring(0, 4).toInt(),
                s.substring(4, 6).toInt(),
                s.substring(6, 8).toInt(),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun List<String>.getOrEmpty(index: Int): String = getOrElse(index) { "" }

    /**
     * ROE 파싱 + DB precision clamp.
     *
     * 정상 raw: "000010.85" 형식 (소수점 포함, 9자리 width, 절댓값 < 100000.00).
     * 비정상 raw: "-36100000" 등 KIS sentinel — ROE 산정 불가 종목(적자/자본잠식/신생/소수점 누락)에 채워짐.
     *
     * NUMERIC(7,2)는 절댓값 < 100000.00만 허용. raw가 sentinel이면 null 반환 + WARN 로그.
     * sentinel을 그대로 BigDecimal로 넘기면 upsert 트랜잭션 전체가 numeric overflow로 rollback되어
     * 해당 row의 다른 필드(name·base_price 등) 갱신이 모두 누락된다.
     */
    private fun sanitizeRoe(
        code: String,
        raw: String,
    ): BigDecimal? {
        val parsed = raw.toBigDecimalOrNull() ?: return null
        if (parsed.abs() >= ROE_DB_LIMIT) {
            log.debug(
                "[instrument-master] ROE sentinel/overflow → null code={} raw='{}' parsed={}",
                code,
                raw,
                parsed,
            )
            return null
        }
        return parsed
    }

    /**
     * KIS 재무 필드(BIGINT) 파싱 + sentinel 안전망.
     *
     * 정상 raw: "000001110" (1110억) 또는 "-00000266" (-266억) 등.
     * sentinel: "-36100000" — KIS "산정 불가" 표식 (현재는 ROE에만 사용되지만 향후 확장 대비).
     *
     * sentinel이면 null + WARN 로그. 일반 미산정(공백/0)은 정상 toLongOrNull 처리.
     */
    private fun sanitizeFinancialLong(
        code: String,
        raw: String,
        fieldName: String,
    ): Long? {
        val trimmed = raw.trim()
        if (trimmed == KIS_SENTINEL_INVALID) {
            log.warn(
                "[instrument-master] {} sentinel → null code={} raw='{}'",
                fieldName,
                code,
                raw,
            )
            return null
        }
        return trimmed.toLongOrNull()
    }

    /**
     * KisInstrumentRaw → 도메인 Instrument 변환 (기존 port 계약 유지용).
     * 도메인 Instrument는 simplicity 유지 (70+ 필드 미포함) — entity 직접 upsert 경로 권장.
     */
    private fun KisInstrumentRaw.toDomain(): Instrument =
        Instrument(
            code = code,
            isin = isin,
            name = name,
            nameEn = null,
            market = market,
            type = type,
            sector = industryMedium,
            listedAt = listedAt?.let { LocalDate(it.year, it.monthValue, it.dayOfMonth) },
            delistedAt = null,
            active = true,
        )
}

/**
 * 종목 마스터 동기화 진행일 (LocalDate 임시 생성 헬퍼).
 * batch 모듈에서 사용.
 */
fun todayKst(): LocalDate {
    val zdt = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
    return LocalDate(zdt.year, zdt.monthValue, zdt.dayOfMonth)
}
