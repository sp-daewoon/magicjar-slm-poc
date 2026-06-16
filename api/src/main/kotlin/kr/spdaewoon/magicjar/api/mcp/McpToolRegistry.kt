package kr.spdaewoon.magicjar.api.mcp

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * MCP tool 6종 등록·라우팅 단일 진입점 (ADR 0027 §2-1 정합).
 *
 * Spring 이 모든 [McpTool] @Component 빈을 list 로 주입 → 본 registry 가 이름 기반 라우팅.
 *
 * ## 부팅 시 의무 검증
 * - tool 이름 중복 시 부팅 fail-fast (silent override 위험 차단)
 * - 등록 tool 0건 시 부팅 fail-fast (Spring DI 누락 회피)
 *
 * ## 4 lens 정합
 * - 세심함: 이름 중복 fail-fast + log INFO 등록 매트릭스
 * - 영향도: tool plug-in pattern — 7번째 tool 추가 시 본체 변경 0 (사용자 페르소나 "확장성" lens 정합)
 * - 연결: Spring DI list 자동 collect
 * - 확장성: McpTool interface 추가 구현만으로 자연 합류
 */
@Component
class McpToolRegistry(
    tools: List<McpTool>,
) {
    private val log = LoggerFactory.getLogger(McpToolRegistry::class.java)
    private val toolsByName: Map<String, McpTool>

    init {
        require(tools.isNotEmpty()) { "MCP tool 0건 — Spring DI 누락 (최소 1개 @Component 필요)" }
        val duplicates = tools.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) {
            "MCP tool 이름 중복: $duplicates (silent override 차단 — fail-fast)"
        }
        toolsByName = tools.associateBy { it.name }
        log.info("[mcp.registry] 등록 tool {}개: {}", toolsByName.size, toolsByName.keys.sorted())
    }

    /** 등록 tool 전체 — `tools/list` 응답 정합 */
    fun list(): List<McpTool> = toolsByName.values.sortedBy { it.name }

    /** 이름으로 tool 조회 — 미존재 시 null (McpController 가 404 한국어 변환) */
    fun find(name: String): McpTool? = toolsByName[name]
}
