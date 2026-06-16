-- V62: KillSwitch capital-path fail-closed 정합을 위한 기본 row seed.
-- 정상 등록 사용자는 enabled=false row를 명시 보유하고, query path에서 row 없음은 확인 불가로 보아 ON 처리한다.

INSERT INTO kill_switch_state (user_id, enabled, reason, updated_at, updated_by)
SELECT ua.user_id,
       false,
       NULL,
       now(),
       'V62_SEED'
FROM user_account ua
ON CONFLICT (user_id) DO NOTHING;

COMMENT ON TABLE kill_switch_state IS
    '사용자별 KillSwitch 상태. 등록 사용자는 enabled=false seed row를 보유하며, 자본 path query에서 row 없음/조회 실패는 fail-closed ON으로 처리';
