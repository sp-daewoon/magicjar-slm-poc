package kr.spdaewoon.magicjar.domain.reconcile.port

/**
 * Reconcile drift 검출 시 KillSwitch auto-ON 토글 포트 (ADR 0043·0070 §2-11).
 *
 * 구현: Phase 2 backend-core 어댑터 또는 기존 `KillSwitchJpaRepository.enableKillSwitch` reuse.
 *
 * 의미:
 *   drift 검출 시 user별 kill_switch_state.enabled=true UPDATE + audit_trail INSERT 의무
 *   (ADR 0043 baseline + ADR 0070 §2-11 step 5).
 *
 * 트리거 reason 컨벤션:
 *   "POSITION_DRIFT" — reconcile drift 검출
 *   "POSITION_BOOTSTRAP_FAILED" — Bootstrap STAGE 0 실패 (ADR 0042, 본 포트 영역 X)
 *
 * @param userId 대상 사용자 ID
 * @param reason 사유 enum 또는 자유 텍스트 (audit_trail 기록)
 * @return idempotent — 이미 enabled=true여도 OK (reason·updatedAt 갱신만)
 */
interface KillSwitchTogglePort {
    fun engage(
        userId: String,
        reason: String,
    )
}
