package kr.spdaewoon.magicjar.consumer.ws

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class KisWsMarketStatusParserTest {
    @Test
    fun `H0UNMKO0 payload에 종목코드가 있으면 코드 기준으로 VI 활성 파싱`() {
        val event = KisWsMarketStatusParser.parseH0Unmko0("005930^N^^1^0^0^0^00^1^0^UN")

        assertThat(event).isNotNull
        assertThat(event?.code).isEqualTo("005930")
        assertThat(event?.viClassCode).isEqualTo("1")
        assertThat(event?.isViActive).isTrue()
    }

    @Test
    fun `공식 column처럼 종목코드가 없으면 fallbackCode 단일 귀속으로만 파싱`() {
        val event = KisWsMarketStatusParser.parseH0Unmko0("N^^1^0^0^0^00^1^0^UN", fallbackCode = "005930")

        assertThat(event).isNotNull
        assertThat(event?.code).isEqualTo("005930")
        assertThat(event?.viClassCode).isEqualTo("1")
        assertThat(event?.exchangeClassCode).isEqualTo("UN")
        assertThat(event?.isViActive).isTrue()
    }

    @Test
    fun `종목코드 없는 H0UNMKO0 payload는 fallback 없으면 안전하게 skip`() {
        val event = KisWsMarketStatusParser.parseH0Unmko0("N^^1^0^0^0^00^1^0^UN")

        assertThat(event).isNull()
    }
}
