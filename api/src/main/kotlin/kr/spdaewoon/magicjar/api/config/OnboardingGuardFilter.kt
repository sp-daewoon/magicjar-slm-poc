package kr.spdaewoon.magicjar.api.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

// ADR 0046, spec 4-7-1 LOCK 정합
// 인증 정책:
//   /api/onboarding/... -> 통과 (셋업 진행 중 언제나 허용)
//   /actuator/...       -> 통과 (헬스 체크)
//   /api/ 외 경로       -> 통과 (정적 리소스, frontend)
//   그 외 /api/...      -> user_account 1행 존재 시 통과, 0행이면 401 + ONBOARDING_REQUIRED
//
// user 세션 정책 (1차 single-tenant 단순화):
//   user_account가 1행 이상 존재 = 셋업 완료 = 모든 API 통과
//   Spring Security, JWT 미도입 (post-MVP 예정)
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class OnboardingGuardFilter(
    private val userAccountJpaRepository: UserAccountJpaRepository,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        req: HttpServletRequest,
        res: HttpServletResponse,
        chain: FilterChain,
    ) {
        val path = req.requestURI

        // 통과 조건: /api/onboarding/..., /api/system-config/..., /api/mcp/..., /actuator/..., /api/ 외 경로
        // /api/system-config/kill-switch GET·PUT — OnboardingGuardFilter 통과 허용 (ADR 0074 §2-6 인증 정책)
        // /api/mcp/** — ApiKeyAuthFilter 단독 인증 (ADR 0027 §2-4 인증 분리 정책 · W4 R2 T-MCP)
        if (path.startsWith("/api/onboarding/") ||
            path.startsWith("/api/system-config/") ||
            path.startsWith("/api/mcp/") ||
            path.startsWith("/actuator/") ||
            !path.startsWith("/api/")
        ) {
            chain.doFilter(req, res)
            return
        }

        // 그 외 /api/... -- 셋업 완료 여부 확인
        if (userAccountJpaRepository.existsAny()) {
            chain.doFilter(req, res)
        } else {
            res.status = HttpServletResponse.SC_UNAUTHORIZED
            res.contentType = "application/json;charset=UTF-8"
            res.writer.write(
                """{"code":"ONBOARDING_REQUIRED","message":"셋업 마법사 완료 후 사용 가능합니다"}""",
            )
        }
    }
}
