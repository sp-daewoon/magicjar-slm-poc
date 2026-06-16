package kr.spdaewoon.magicjar.api.infrastructure.observability

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * MDC traceId 분산 추적 servlet filter (ADR 0084 P1-3 — spec §2-7 LOCK).
 *
 * ### 목적
 * HTTP request 단위로 unique traceId를 생성·전파하여 사고 발생 시
 * `HTTP → Kafka → consumer → KIS REST → KIS WS` 전체 흐름을 단일 ID로 추적.
 *
 * ### 동작
 * 1. 요청 진입 시 `X-Trace-Id` 헤더 추출 (외부 system 전달 시 그대로 재사용 — 분산 추적 chain).
 * 2. 없으면 UUID v4 32-hex(`-` 제거) 신규 생성.
 * 3. `MDC.put("traceId", id)` — logback `%X{traceId}` 패턴이 같은 thread 로그에 자동 부착.
 * 4. response header `X-Trace-Id` 회신 — 운영자가 사고 시점 traceId 즉시 추출 가능.
 * 5. finally `MDC.clear()` — 메모리 leak·다음 request 오염 방지.
 *
 * ### Order
 * `Ordered.HIGHEST_PRECEDENCE` — OnboardingGuardFilter(HIGHEST_PRECEDENCE+10) 등
 * 다른 필터 진입 전 traceId 부착 → 인증·인가·비즈 로직 로그 전체에 동일 traceId 부착.
 *
 * ### 비범위 (P1-3 spec §2-7)
 * - Jaeger/Zipkin 분산 trace UI 연동 — W5 이후 (Micrometer Tracing 도입 시).
 * - sampling·child span — Brave/OpenTelemetry 미도입.
 *
 * AI 호출 0 — ADR 0084 §2-8.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class TraceIdServletFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val incoming = request.getHeader(HEADER_TRACE_ID)
        val traceId = if (incoming.isNullOrBlank()) generateTraceId() else incoming.trim()

        MDC.put(MDC_KEY_TRACE_ID, traceId)
        response.setHeader(HEADER_TRACE_ID, traceId)

        try {
            chain.doFilter(request, response)
        } finally {
            // 메모리 leak·다음 request 오염 방지 — finally LOCK
            MDC.remove(MDC_KEY_TRACE_ID)
        }
    }

    /** UUID v4 32-hex (`-` 제거) — 운영 로그 가독성 향상 (`a1b2c3d4...` 형태). */
    private fun generateTraceId(): String = UUID.randomUUID().toString().replace("-", "")

    companion object {
        /** MDC key — logback pattern `%X{traceId}` 정합. */
        const val MDC_KEY_TRACE_ID = "traceId"

        /** HTTP header — 외부 system·response 회신 정합. */
        const val HEADER_TRACE_ID = "X-Trace-Id"
    }
}
