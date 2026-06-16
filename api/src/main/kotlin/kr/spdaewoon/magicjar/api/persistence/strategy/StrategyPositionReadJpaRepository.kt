package kr.spdaewoon.magicjar.api.persistence.strategy

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * strategy_position **read-only** Repository (api 모듈 — ADR 0110 V64, 분할매수 R3-a).
 *
 * 전략별 포지션 표시용 조회 전용. 쓰기(upsert)는 consumer 모듈 책임.
 * 헌법 §6 (ADR 0051): body 가진 default fun 0건 — query inference 메서드만.
 */
@Repository
interface StrategyPositionReadJpaRepository : JpaRepository<StrategyPositionReadJpaEntity, Long> {
    /** 전략 단위 보유 행 조회(UQ user+strategy+code라 전략당 종목별 1행 — 전략에 종목 1개면 단건). */
    fun findByStrategyId(strategyId: Long): List<StrategyPositionReadJpaEntity>

    /**
     * ADR 0115 §2-B 키 사슬 추적성 (불변식 #4) — user+strategy+code 단건 보유 조회 (read-only).
     *
     * 시그널 추적 드릴다운에서 "현재 그 전략이 이 종목 몇 주 보유" 노출. UQ라 0~1행.
     */
    fun findByUserIdAndStrategyIdAndCode(
        userId: String,
        strategyId: Long,
        code: String,
    ): StrategyPositionReadJpaEntity?
}
