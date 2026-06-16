package kr.spdaewoon.magicjar.api.infrastructure.observability

import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/**
 * TraceIdServletFilter 단위 테스트 — ADR 0084 P1-3 정합.
 *
 * 검증 case:
 * 1. 외부 X-Trace-Id header 진입 시 그대로 MDC + response 회신
 * 2. header 부재 시 UUID 신규 생성 (32-hex)
 * 3. finally MDC clear — 다음 request 오염 방지
 * 4. response header 항상 부착
 */
class TraceIdServletFilterTest {
    private val filter = TraceIdServletFilter()

    @AfterEach
    fun cleanup() {
        // 테스트 간 MDC leak 방지
        MDC.remove(TraceIdServletFilter.MDC_KEY_TRACE_ID)
    }

    @Test
    fun `외부 X-Trace-Id header 진입 시 그대로 MDC + response 회신`() {
        val req = MockHttpServletRequest("GET", "/api/orders")
        val externalTraceId = "external-trace-1234567890abcdef"
        req.addHeader(TraceIdServletFilter.HEADER_TRACE_ID, externalTraceId)
        val res = MockHttpServletResponse()

        var observedInMdcDuringChain: String? = null
        val chain =
            FilterChain { _, _ ->
                observedInMdcDuringChain = MDC.get(TraceIdServletFilter.MDC_KEY_TRACE_ID)
            }

        filter.doFilter(req, res, chain)

        assertThat(observedInMdcDuringChain)
            .`as`("chain 실행 중 MDC traceId가 외부 헤더와 일치해야 함")
            .isEqualTo(externalTraceId)
        assertThat(res.getHeader(TraceIdServletFilter.HEADER_TRACE_ID))
            .`as`("response header에 traceId 회신 의무")
            .isEqualTo(externalTraceId)
    }

    @Test
    fun `header 부재 시 UUID 신규 생성 — 32-hex 형식`() {
        val req = MockHttpServletRequest("POST", "/api/orders")
        val res = MockHttpServletResponse()

        var observedInMdcDuringChain: String? = null
        val chain =
            FilterChain { _, _ ->
                observedInMdcDuringChain = MDC.get(TraceIdServletFilter.MDC_KEY_TRACE_ID)
            }

        filter.doFilter(req, res, chain)

        assertThat(observedInMdcDuringChain).`as`("신규 traceId 생성 의무").isNotNull()
        // UUID v4 32-hex (`-` 제거) 형식 검증
        assertThat(observedInMdcDuringChain!!.length).`as`("UUID 32-hex 형식").isEqualTo(32)
        assertThat(observedInMdcDuringChain!!.matches(Regex("[0-9a-f]+")))
            .`as`("hex 문자만 포함")
            .isTrue()
        assertThat(res.getHeader(TraceIdServletFilter.HEADER_TRACE_ID))
            .`as`("신규 traceId도 response header 회신")
            .isEqualTo(observedInMdcDuringChain)
    }

    @Test
    fun `chain 실행 후 MDC clear — 다음 request 오염 방지`() {
        val req = MockHttpServletRequest("GET", "/api/orders")
        val res = MockHttpServletResponse()
        // chain 본체는 no-op — filter 종료 후 MDC clear 검증이 목적
        val chain = FilterChain { _, _ -> }

        filter.doFilter(req, res, chain)

        assertThat(MDC.get(TraceIdServletFilter.MDC_KEY_TRACE_ID))
            .`as`("filter 종료 후 MDC.traceId가 clear 되어야 함")
            .isNull()
    }

    @Test
    fun `chain 예외 발생 시에도 MDC clear`() {
        val req = MockHttpServletRequest("GET", "/api/orders")
        val res = MockHttpServletResponse()
        val chain =
            FilterChain { _, _ ->
                throw RuntimeException("chain 내부 오류")
            }

        try {
            filter.doFilter(req, res, chain)
        } catch (_: RuntimeException) {
            // 예외 흐름 진입 OK
        }

        assertThat(MDC.get(TraceIdServletFilter.MDC_KEY_TRACE_ID))
            .`as`("예외 발생해도 finally가 MDC.traceId clear 의무")
            .isNull()
    }

    @Test
    fun `빈 header 값 시 신규 생성`() {
        val req = MockHttpServletRequest("GET", "/api/orders")
        req.addHeader(TraceIdServletFilter.HEADER_TRACE_ID, "   ")
        val res = MockHttpServletResponse()
        var observed: String? = null
        val chain = FilterChain { _, _ -> observed = MDC.get(TraceIdServletFilter.MDC_KEY_TRACE_ID) }

        filter.doFilter(req, res, chain)

        assertThat(observed).isNotNull()
        assertThat(observed!!.length).`as`("공백 header은 신규 UUID 생성").isEqualTo(32)
    }
}
