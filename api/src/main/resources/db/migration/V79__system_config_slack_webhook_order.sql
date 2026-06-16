-- V79 — 주문 체결 알림 전용 Slack webhook 추가 채널 (2026-06-10 최고 관리자 지시 · 이중 송출)
--
-- 배경: 주문 체결(ORDER_FILLED)은 중요 알람이라 기존 채널(notification_slack_webhook) 유지 +
--       별도 Slack 채널로 추가 송출. 본 컬럼은 추가 채널 webhook URL을 보관한다.
--
-- 정합:
--   - ADR 0046 AES-256-GCM — BYTEA 암호화 단일 row(system_config id=1) 패턴 1:1 (notification_slack_webhook 동일).
--   - 미설정(NULL) 시 추가 송출 silent skip — 기존 채널 발송/outbox에 영향 0.
--   - 실제 webhook URL은 어떤 파일에도 넣지 않는다(코드·yml·.env 모두 placeholder). 값 주입은 운영자가 DB에서 수행.
ALTER TABLE system_config ADD COLUMN IF NOT EXISTS notification_slack_webhook_order BYTEA;
