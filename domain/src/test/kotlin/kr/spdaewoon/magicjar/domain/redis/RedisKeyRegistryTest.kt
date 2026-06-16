package kr.spdaewoon.magicjar.domain.redis

import io.kotest.matchers.shouldBe
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.junit.jupiter.api.Test

class RedisKeyRegistryTest {
    @Test
    fun `current account and kis keys use standard wire format`() {
        RedisKeyRegistry.Account.balance("74683223", AccountType.REAL) shouldBe
            "account:balance:user:74683223:account:REAL"
        RedisKeyRegistry.Account.balanceLock("74683223", AccountType.REAL) shouldBe
            "account:balance:user:74683223:account:REAL:lock"
        RedisKeyRegistry.Kis.token("74683223", AccountType.MOCK) shouldBe
            "kis:oauth:token:user:74683223:account:MOCK"
        RedisKeyRegistry.Kis.tokenLock("74683223", AccountType.MOCK) shouldBe
            "kis:oauth:token:user:74683223:account:MOCK:lock"
        RedisKeyRegistry.Kis.approval("74683223", AccountType.REAL) shouldBe
            "kis:ws:approval:user:74683223:account:REAL"
    }

    @Test
    fun `standard keys follow labelled dimension convention`() {
        RedisKeyRegistry.Account.Standard.balance("74683223", "REAL") shouldBe
            "account:balance:user:74683223:account:REAL"
        RedisKeyRegistry.Market.Standard.quoteLast("005930") shouldBe "market:quote:last:code:005930"
        RedisKeyRegistry.Ops.Standard.watchlistSource("74683223") shouldBe "ops:watchlist:source:user:74683223"
    }

    @Test
    fun `market current keys use standard wire format and parse candle session keys`() {
        RedisKeyRegistry.Market.quoteLast("005930") shouldBe "market:quote:last:code:005930"
        RedisKeyRegistry.Market.orderbookTop("005930") shouldBe "market:orderbook:top:code:005930"
        RedisKeyRegistry.Market.candleCurrent("005930", "1m") shouldBe
            "market:candle:current:code:005930:interval:1m"
        RedisKeyRegistry.Market.candleToday1d("005930") shouldBe
            "market:candle:session:code:005930:interval:1d"
        RedisKeyRegistry.Market.candleToday1dPattern() shouldBe "market:candle:session:code:*:interval:1d"
        RedisKeyRegistry.Market.candleUniverseDetailRecent() shouldBe "market:candle:universe:detail-recent"
        RedisKeyRegistry.Market.candleUniverseStrategyCandidates() shouldBe "market:candle:universe:strategy-candidates"
        RedisKeyRegistry.Market.codeFromCandleToday1dKey("market:candle:session:code:005930:interval:1d") shouldBe "005930"
    }

    @Test
    fun `legacy candle today parser remains tolerant during rolling cleanup`() {
        RedisKeyRegistry.Market.codeFromCandleToday1dKey("candle:today:005930:1d") shouldBe "005930"
    }
}
