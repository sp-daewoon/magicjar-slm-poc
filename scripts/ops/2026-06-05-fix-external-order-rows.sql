-- 2026-06-05 운영 보정 — 외부(KIS HTS) 주문 흡수 시 user_id·mode·source 오기록 정정
--
-- 사유 (라이브 실측 2026-06-05 15:08~15:15 KST):
--   사용자가 KIS HTS 앱에서 직접 004090 5주 매수(모의)를 했고, 시스템이 체결통보(H0STCNI9)/
--   REST backfill 로 흡수하면서 OrderRequest stub row 3건을 3중으로 오기록했다.
--     1. user_id = 'thdeodns'  — KIS htsId 그대로 (user_account 74683223 에 없음) → position upsert FK 위반
--     2. mode    = 'REAL'      — 모의 체결인데 REAL 기록 → ADR 0109 canary 일일 cap(mode=REAL count) 오염
--     3. source  = 'AUTO'      — 외부 수동 주문인데 자동매매로 분류 → 감사 추적 혼선
--
--   실측 대상 row (order_request):
--     id=135  kis_order_no=0000030801  status=ACK
--     id=136  kis_order_no=0000030809  status=ACK
--     id=137  kis_order_no=0000030820  status=ACK
--
-- 보정 값:
--     user_id -> '74683223' (내부 userId · user_account.user_id)
--     mode    -> 'MOCK'      (모의 체결통보 H0STCNI9 정합)
--     source  -> 'EXTERNAL'  (외부 흡수 — Flyway V66 CHECK 확장 후 유효)
--
-- 사전 조건 (의무):
--   - Flyway V66__order_request_source_external.sql 적용 완료 (source CHECK 에 'EXTERNAL' 포함) 후 실행.
--     미적용 상태에서 실행하면 source='EXTERNAL' UPDATE 가 CHECK 23514 로 실패한다.
--   - 내부 user '74683223' 가 user_account 에 실제 존재해야 한다 (없으면 user_id UPDATE 가 FK 위반).
--     확인: SELECT user_id FROM user_account WHERE user_id = '74683223';
--
-- 안전 조건:
--   - id + kis_order_no 동시 매칭으로 정확한 row 만 대상 (오타·번호 shift 방어).
--   - 현재 잘못 박힌 값(user_id='thdeodns' AND mode='REAL' AND source='AUTO') 일 때만 UPDATE
--     → 이미 보정됐거나 다른 상태면 no-op (재실행 멱등).
--   - order_request.source·mode·user_id 컬럼은 엔티티상 updatable=false 지만 DB 레벨 UPDATE 는 가능
--     (JPA 매핑 제약일 뿐 DB 제약 아님). 본 보정은 운영 DB 직접 UPDATE.
--
-- 코드 fix 동반: agent/backend-core/external-order-user-mapping
--   - WS preInsert: ExternalOrderAccountResolver 로 ACNT_NO → 내부 userId·mode 매핑 + source=EXTERNAL
--   - backfill preInsert: source=EXTERNAL
--   - canary count: source <> 'EXTERNAL' 제외
--
-- 실행: orchestrator 가 코드 PR 머지(→ Flyway V66 부팅 적용) 후 운영 DB 에 수동 적용.

-- 사전 확인 (참고용 — 실행 전 눈으로 검증 권장)
-- SELECT id, kis_order_no, user_id, mode, source, status, requested_at
-- FROM order_request
-- WHERE id IN (135, 136, 137);

UPDATE order_request
SET user_id = '74683223',
    mode    = 'MOCK',
    source  = 'EXTERNAL'
WHERE id IN (135, 136, 137)
  AND kis_order_no IN ('0000030801', '0000030809', '0000030820')
  AND user_id = 'thdeodns'
  AND mode = 'REAL'
  AND source = 'AUTO';

-- 사후 확인
-- SELECT id, kis_order_no, user_id, mode, source FROM order_request WHERE id IN (135, 136, 137);
-- 기대: 3 rows · user_id='74683223' · mode='MOCK' · source='EXTERNAL'
