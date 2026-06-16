package kr.spdaewoon.magicjar.consumer.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * WatchlistPollingWorker 단위 테스트 — **시세 5초 REST polling 폐지** (ADR 0124 amend · 2026-06-15 결재).
 *
 * 검증 목표:
 * 1. watchlist pool 등록/제거/조회 기능은 유지된다(등록/삭제/priority 기능 보존).
 * 2. start/stop 상태 플래그만 토글하고 KIS REST 시세 조회는 더 이상 하지 않는다(EGW00201 quota 낭비 제거).
 *
 * 배경: 기존 5초 `@Scheduled tick()` 은 watchlist 종목 현재가를 KIS REST(FHKST01010100)로 polling 해
 * quote:last 캐시를 채웠으나, 운영 실측상 KIS EGW00201(초당 거래건수 초과)을 유발하고 USER bucket(주문·잔고)과
 * 경합했다. quote:last 라이브 공급은 본 polling 단일 경로였고 최고 관리자가 watchlist 시세 화면을 미사용 →
 * 순수 quota 낭비로 결재되어 자동 조회 책임이 폐지됐다.
 */
class WatchlistPollingWorkerTest {
    private lateinit var worker: WatchlistPollingWorker

    @BeforeEach
    fun setUp() {
        worker = WatchlistPollingWorker()
    }

    @Test
    fun `watchlist pool add 시 poolSize 증가 + poolCodes 반영`() {
        worker.add("005930")
        worker.add("000660")

        assertThat(worker.poolSize()).isEqualTo(2)
        assertThat(worker.poolCodes()).containsExactlyInAnyOrder("005930", "000660")
    }

    @Test
    fun `중복 add 는 멱등 (set 기반)`() {
        worker.add("005930")
        worker.add("005930")

        assertThat(worker.poolSize()).isEqualTo(1)
    }

    @Test
    fun `watchlist pool remove 시 poolSize 감소`() {
        worker.add("005930")
        worker.add("000660")

        worker.remove("005930")

        assertThat(worker.poolSize()).isEqualTo(1)
        assertThat(worker.poolCodes()).containsExactly("000660")
    }

    @Test
    fun `start stop 은 running 플래그만 토글한다 (시세 조회 없음)`() {
        assertThat(worker.running).isFalse()

        worker.start()
        assertThat(worker.running).isTrue()

        worker.stop()
        assertThat(worker.running).isFalse()
    }

    @Test
    fun `start 해도 KIS REST 시세 조회 의존성이 없다 (자동 polling 폐지)`() {
        // ADR 0124 amend: worker 는 무인자 생성자다(RedisQuoteCache·KisRestPriceAdapter·credential 의존 제거).
        // 시세 자동 조회 경로가 코드에서 사라졌음을 생성자 시그니처가 보증한다.
        worker.add("005930")
        worker.start()

        // pool 관리만 살아있고 어떤 외부 KIS 호출도 트리거되지 않는다(의존성 0 → 호출 불가).
        assertThat(worker.poolCodes()).containsExactly("005930")
        assertThat(worker.running).isTrue()
    }
}
