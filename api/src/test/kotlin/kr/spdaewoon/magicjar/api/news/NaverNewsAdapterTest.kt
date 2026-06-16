package kr.spdaewoon.magicjar.api.news

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.infrastructure.naver.NaverNewsAdapter
import kr.spdaewoon.magicjar.api.persistence.NewsArticleJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/**
 * NaverNewsAdapter 단위 테스트 (T-NEWS Phase 2 — ADR 0075 §11-2 grep 검증 정합).
 *
 * Spring 컨텍스트 없이 순수 비즈니스 로직만 검증 (mock 주입).
 *
 * 검증 1: <b> 태그 strip — "<b>삼성전자</b>" → "삼성전자"
 * 검증 2: pubDate RFC 822 파싱 — "Mon, 26 May 2026 07:50:00 +0900" → Instant
 * 검증 3: url_hash SHA-256 멱등성 — 동일 URL 2회 동일 hash
 * 검증 4: API 키 미설정 시 graceful skip (빈 리스트 반환)
 *
 * // AI 호출 0 건 (ADR 0075 §11-2 정합)
 */
class NaverNewsAdapterTest {
    private lateinit var naverNewsAdapter: NaverNewsAdapter
    private lateinit var newsArticleJpaRepository: NewsArticleJpaRepository

    @BeforeEach
    fun setUp() {
        newsArticleJpaRepository = mock()
        // clientId/clientSecret 빈 문자열 → graceful skip 테스트 가능
        naverNewsAdapter =
            NaverNewsAdapter(
                newsArticleJpaRepository = newsArticleJpaRepository,
                objectMapper = ObjectMapper(),
                clientId = "",
                clientSecret = "",
            )
    }

    /**
     * 검증 1: <b> 태그 strip
     * ADR 0075 spec §2-2 결정 4 — 검색어 강조 태그 제거 의무
     */
    @Test
    fun `stripBTag - b 태그 제거`() {
        // given
        val input = "<b>삼성전자</b> 메모리 반도체 <b>가격</b> 인상 검토"
        // when
        val result = naverNewsAdapter.stripBTag(input)
        // then
        assertThat(result).isEqualTo("삼성전자 메모리 반도체 가격 인상 검토")
        assertThat(result).doesNotContain("<b>", "</b>")
    }

    /**
     * 검증 1b: 대소문자 혼합 <B> 태그 strip
     */
    @Test
    fun `stripBTag - 대소문자 혼합 B 태그 제거`() {
        // given
        val input = "<B>삼성전자</B> 반도체"
        // when
        val result = naverNewsAdapter.stripBTag(input)
        // then
        assertThat(result).isEqualTo("삼성전자 반도체")
    }

    /**
     * 검증 2: pubDate RFC 822 파싱
     * ADR 0075 spec §2-2 결정 4 — Naver pubDate 형식: "Mon, 26 May 2026 07:50:00 +0900"
     */
    @Test
    fun `parsePubDate - RFC 822 날짜 파싱`() {
        // given (ADR 0075 §7-5 pubDate 예시 형식)
        val pubDate = "Mon, 26 May 2026 07:50:00 +0900"
        // when
        val result = naverNewsAdapter.parsePubDate(pubDate)
        // then: null이 아니고 적절한 Instant 반환 (파싱 성공 검증)
        assertThat(result).isNotNull
        // UTC 기준 2026-05-25T22:50:00Z 와 동일한 epoch 초 검증
        val expectedEpochSecond =
            java.time.OffsetDateTime
                .parse("2026-05-26T07:50:00+09:00")
                .toEpochSecond()
        assertThat(result!!.epochSecond).isEqualTo(expectedEpochSecond)
    }

    /**
     * 검증 2b: 잘못된 pubDate → null 반환 (graceful)
     */
    @Test
    fun `parsePubDate - 잘못된 형식 null 반환`() {
        // given
        val pubDate = "invalid-date"
        // when
        val result = naverNewsAdapter.parsePubDate(pubDate)
        // then
        assertThat(result).isNull()
    }

    /**
     * 검증 3: url_hash SHA-256 멱등성
     * ADR 0075 §2-2 — 동일 URL 2회 호출 시 동일 hash (64자 hex)
     */
    @Test
    fun `sha256Hex - 동일 URL 멱등성`() {
        // given
        val url = "https://n.news.naver.com/article/001/0012345678"
        // when
        val hash1 = naverNewsAdapter.sha256Hex(url)
        val hash2 = naverNewsAdapter.sha256Hex(url)
        // then
        assertThat(hash1).isEqualTo(hash2)
        assertThat(hash1).hasSize(64)
        assertThat(hash1).matches("[0-9a-f]{64}")
    }

    /**
     * 검증 3b: 다른 URL → 다른 hash
     */
    @Test
    fun `sha256Hex - 다른 URL 다른 hash`() {
        // given
        val url1 = "https://n.news.naver.com/article/001/0012345678"
        val url2 = "https://n.news.naver.com/article/001/0099999999"
        // when
        val hash1 = naverNewsAdapter.sha256Hex(url1)
        val hash2 = naverNewsAdapter.sha256Hex(url2)
        // then
        assertThat(hash1).isNotEqualTo(hash2)
    }

    /**
     * 검증 4: API 키 미설정 시 graceful skip
     * ADR 0075 spec §2-2 결정 6 — clientId 빈 문자열 → fetchAndSave 빈 리스트 반환
     */
    @Test
    fun `fetchAndSave - API 키 미설정 graceful skip`() {
        // NaverNewsAdapter는 clientId="" 로 직접 생성됨
        // when
        val result = naverNewsAdapter.fetchAndSave("005930", "삼성전자")
        // then
        assertThat(result).isEmpty()
    }
}
