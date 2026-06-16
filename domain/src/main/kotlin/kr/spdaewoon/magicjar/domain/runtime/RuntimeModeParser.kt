package kr.spdaewoon.magicjar.domain.runtime

import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.user.AccountType

/**
 * Runtime REAL/MOCK 문자열 정규화.
 *
 * 환경변수는 운영자가 직접 넣는 값이라 mode의 `real`, ` REAL `, allow-real의 `True` 같은
 * 표기가 들어와도 거래 계층 전체가 같은 판단을 하도록 한 곳에서만 해석한다.
 */
object RuntimeModeParser {
    fun isRealMode(raw: String?): Boolean = raw?.trim()?.equals("REAL", ignoreCase = true) == true

    fun tradingModeOrMock(raw: String?): TradingMode = if (isRealMode(raw)) TradingMode.REAL else TradingMode.MOCK

    fun accountTypeOrMock(raw: String?): AccountType = if (isRealMode(raw)) AccountType.REAL else AccountType.MOCK

    fun allowReal(raw: String?): Boolean = raw?.trim()?.equals("true", ignoreCase = true) == true
}
