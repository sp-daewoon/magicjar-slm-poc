package kr.spdaewoon.magicjar.consumer.persistence

import kotlinx.datetime.toKotlinInstant
import kr.spdaewoon.magicjar.domain.watchlist.WatchlistEntry
import kr.spdaewoon.magicjar.domain.watchlist.port.WatchlistRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * WatchlistRepository 도메인 포트 구현 — consumer 모듈 (읽기 전용 · 부팅 reconcile 용).
 */
@Component
class ConsumerWatchlistRepositoryAdapter(
    private val repo: ConsumerWatchlistJpaRepository,
) : WatchlistRepository {
    @Transactional(readOnly = true)
    override fun findByUserId(userId: String): List<WatchlistEntry> =
        repo.findByUserIdOrderByPriorityAscCreatedAtDesc(userId).map { it.toDomain() }

    @Transactional(readOnly = true)
    override fun findByUserIdOrderByPriority(userId: String): List<WatchlistEntry> =
        repo.findByUserIdOrderByPriorityAscCreatedAtDesc(userId).map { it.toDomain() }

    override fun add(entry: WatchlistEntry): WatchlistEntry {
        // consumer 모듈은 add 불필요 — api 모듈 담당
        throw UnsupportedOperationException("Watchlist 추가는 api 모듈에서만 처리합니다")
    }

    override fun remove(
        userId: String,
        code: String,
    ): Boolean {
        // consumer 모듈은 remove 불필요 — api 모듈 담당
        throw UnsupportedOperationException("Watchlist 삭제는 api 모듈에서만 처리합니다")
    }

    override fun existsByUserIdAndCode(
        userId: String,
        code: String,
    ): Boolean = repo.findByUserIdOrderByPriorityAscCreatedAtDesc(userId).any { it.code == code }

    /**
     * 모든 사용자 watchlist 목록 (부팅 reconcile용).
     */
    @Transactional(readOnly = true)
    fun findAll(): List<WatchlistEntry> = repo.findAllByOrderByPriorityAscCreatedAtDesc().map { it.toDomain() }

    private fun ConsumerWatchlistJpaEntity.toDomain(): WatchlistEntry =
        WatchlistEntry(
            id = id,
            userId = userId,
            code = code,
            priority = priority,
            createdAt = createdAt.toKotlinInstant(),
        )
}
