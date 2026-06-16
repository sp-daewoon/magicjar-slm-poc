package kr.spdaewoon.magicjar.domain.notification.port

import kr.spdaewoon.magicjar.domain.common.DomainResult
import kr.spdaewoon.magicjar.domain.notification.NotificationEvent

/**
 * 알림 발송 포트 (ADR 0044).
 * 구현은 consumer 모듈 notification 어댑터에서 (Slack webhook 등).
 */
interface NotificationSender {
    fun send(event: NotificationEvent): DomainResult<Unit>
}
