package kr.spdaewoon.magicjar.api.service

import kr.spdaewoon.magicjar.api.controller.SystemEventPageDto
import kr.spdaewoon.magicjar.api.controller.SystemEventResponseDto
import kr.spdaewoon.magicjar.persistence.shared.event.SystemEventJpaEntity
import kr.spdaewoon.magicjar.persistence.shared.event.SystemEventJpaRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * P2 — 시스템 이벤트 조회·마킹 service.
 *
 * 3 op:
 * - [findList]   : 사용자별/전체 페이지네이션 + unread count 계산
 * - [markViewed] : viewed_at = now 마킹 (사용자 확인 → 벨 badge 감소)
 * - [markResolved] : resolved_at = now 마킹 (운영자 dismiss → dashboard 시각화)
 *                   ★ viewed_at IS NULL 일 때 viewed_at = now 동반 마킹 (사용자 mental model 정합)
 *                   사유: 사용자 dogfooding(2026-05-20) — "해결 표시" 9건 처리해도 "미확인 9건"
 *                   카운트 그대로 + "미확인만" 필터로 사라지지 않는 mismatch.
 *                   "해결 = 확인된 것"이 사용자 mental model — viewed_at NOT NULL 인 경우(이미 확인됨)
 *                   는 그대로 두어 idempotent 보장.
 *
 * ### findList filter 매트릭스 (P2.1 — final reviewer ★★ #2 fix)
 *
 * | userId | unreadOnly | items                                                    | totalCount                       | unreadCount                          |
 * |--------|------------|----------------------------------------------------------|----------------------------------|--------------------------------------|
 * | Y      | true       | findByUserIdAndViewedAtIsNullOrderByOccurredAtDesc       | countByUserIdAndViewedAtIsNull   | countByUserIdAndViewedAtIsNull       |
 * | Y      | false      | findByUserIdOrderByOccurredAtDesc                        | countByUserId                    | countByUserIdAndViewedAtIsNull       |
 * | N      | true       | findByViewedAtIsNullOrderByOccurredAtDesc                | countByViewedAtIsNull            | countByViewedAtIsNull                |
 * | N      | false      | findAllByOrderByOccurredAtDesc                           | jpa.count()                      | countByViewedAtIsNull                |
 *
 * 결함 fix:
 * - userId null + unreadOnly=true → 기존 코드는 전체 반환 (filter 무시) → 4 case 정확 분기로 정정
 * - totalCount 가 항상 jpa.count() → 사용자/unread filter 무시 → filter 정합 count 사용
 * - userId null 시 unreadCount=0 → frontend dashboard 미확인 표시 정확성 손실 → countByViewedAtIsNull 사용
 *
 * 부가 개선 (진보적 페르소나 — 보일러플레이트 제거):
 * - 기존 findBy*( ..., PageRequest.of(0, Int.MAX_VALUE)).size.toLong() 풀로딩 패턴 폐기 →
 *   countBy* derived query 로 efficient COUNT(*) 전환 (SystemEventJpaRepository P2.1 신규 finder)
 *
 * ### 헌법 §6 + ADR 0051 정합
 * Repository interface 안에 body 가진 fun 0건 — `toResponse()` mapper는 본 service 의
 * private extension fun 으로 격리 (default fun 함정 회피).
 *
 * ### 트랜잭션
 * - findList: `@Transactional(readOnly = true)`
 * - markViewed/markResolved: 기본 `@Transactional` (write — Hibernate dirty checking)
 *
 * spec: `_workspace/specs/2026-05-20-system-event-log-design.md` §6 + §13
 * plan: `_workspace/plans/2026-05-20-system-event-log-p2-implementation-plan.md` Task 2
 *
 * AI 호출 0 — ADR 0023 §2-4 정합.
 */
@Service
class SystemEventQueryService(
    private val jpa: SystemEventJpaRepository,
) {
    @Transactional(readOnly = true)
    fun findList(
        userId: String?,
        unreadOnly: Boolean,
        page: Int,
        size: Int,
    ): SystemEventPageDto {
        val pageable = PageRequest.of(page, size)
        val (items, totalCount, unreadCount) =
            when {
                userId != null && unreadOnly -> {
                    val unread = jpa.countByUserIdAndViewedAtIsNull(userId)
                    Triple(
                        jpa.findByUserIdAndViewedAtIsNullOrderByOccurredAtDesc(userId, pageable),
                        unread,
                        unread,
                    )
                }
                userId != null ->
                    Triple(
                        jpa.findByUserIdOrderByOccurredAtDesc(userId, pageable),
                        jpa.countByUserId(userId),
                        jpa.countByUserIdAndViewedAtIsNull(userId),
                    )
                unreadOnly -> {
                    val unread = jpa.countByViewedAtIsNull()
                    Triple(
                        jpa.findByViewedAtIsNullOrderByOccurredAtDesc(pageable),
                        unread,
                        unread,
                    )
                }
                else ->
                    Triple(
                        jpa.findAllByOrderByOccurredAtDesc(pageable),
                        jpa.count(),
                        jpa.countByViewedAtIsNull(),
                    )
            }
        return SystemEventPageDto(
            items = items.map { it.toResponse() },
            totalCount = totalCount,
            unreadCount = unreadCount,
            page = page,
            size = size,
        )
    }

    @Transactional
    fun markViewed(id: Long): SystemEventResponseDto? {
        val entity = jpa.findById(id).orElse(null) ?: return null
        entity.viewedAt = Instant.now()
        return jpa.save(entity).toResponse()
    }

    @Transactional
    fun markResolved(id: Long): SystemEventResponseDto? {
        val entity = jpa.findById(id).orElse(null) ?: return null
        val now = Instant.now()
        entity.resolvedAt = now
        // 사용자 mental model: "해결 = 확인된 것" — viewed_at NULL 일 때만 동반 마킹 (idempotent)
        entity.viewedAt = entity.viewedAt ?: now
        return jpa.save(entity).toResponse()
    }

    private fun SystemEventJpaEntity.toResponse(): SystemEventResponseDto =
        SystemEventResponseDto(
            id = id ?: 0L,
            category = category,
            severity = severity,
            actor = actor,
            action = action,
            userId = userId,
            correlationId = correlationId,
            detailJson = detailJson,
            occurredAt = occurredAt,
            viewedAt = viewedAt,
            resolvedAt = resolvedAt,
        )
}
