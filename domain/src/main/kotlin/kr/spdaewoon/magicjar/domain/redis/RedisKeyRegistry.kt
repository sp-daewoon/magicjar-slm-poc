package kr.spdaewoon.magicjar.domain.redis

import kr.spdaewoon.magicjar.domain.user.AccountType

/**
 * Redis key registry.
 *
 * This object is intentionally dependency-free so api, consumer, batch, and library modules
 * can share Redis key construction without pulling Spring/Redis clients into domain.
 *
 * Current functions return the standard production wire keys. Legacy wire keys are intentionally
 * not generated from application paths after the Redis naming cutover.
 */
object RedisKeyRegistry {
    object Kis {
        fun token(
            userId: String,
            accountType: AccountType,
        ): String = token(userId, accountType.name)

        fun token(
            userId: String,
            accountType: String,
        ): String = Standard.token(userId, accountType)

        fun tokenLock(
            userId: String,
            accountType: AccountType,
        ): String = tokenLock(userId, accountType.name)

        fun tokenLock(
            userId: String,
            accountType: String,
        ): String = Standard.tokenLock(userId, accountType)

        fun approval(
            userId: String,
            accountType: AccountType,
        ): String = approval(userId, accountType.name)

        fun approval(
            userId: String,
            accountType: String,
        ): String = Standard.approval(userId, accountType)

        fun approvalLock(
            userId: String,
            accountType: AccountType,
        ): String = approvalLock(userId, accountType.name)

        fun approvalLock(
            userId: String,
            accountType: String,
        ): String = Standard.approvalLock(userId, accountType)

        fun keyChangedLock(
            userId: String,
            accountType: String,
        ): String = Standard.keyChangedLock(userId, accountType)

        object Standard {
            fun token(
                userId: String,
                accountType: String,
            ): String = "kis:oauth:token:user:$userId:account:$accountType"

            fun tokenLock(
                userId: String,
                accountType: String,
            ): String = "kis:oauth:token:user:$userId:account:$accountType:lock"

            fun approval(
                userId: String,
                accountType: String,
            ): String = "kis:ws:approval:user:$userId:account:$accountType"

            fun approvalLock(
                userId: String,
                accountType: String,
            ): String = "kis:ws:approval:user:$userId:account:$accountType:lock"

            fun keyChangedLock(
                userId: String,
                accountType: String,
            ): String = "kis:credential:changed:user:$userId:account:$accountType"
        }
    }

    object Account {
        fun balance(
            userId: String,
            accountType: AccountType,
        ): String = balance(userId, accountType.name)

        fun balance(
            userId: String,
            accountType: String,
        ): String = Standard.balance(userId, accountType)

        fun balanceLock(
            userId: String,
            accountType: AccountType,
        ): String = balanceLock(userId, accountType.name)

        fun balanceLock(
            userId: String,
            accountType: String,
        ): String = Standard.balanceLock(userId, accountType)

        fun availability(
            mode: String,
            userId: String,
        ): String = Standard.availability(userId, mode)

        fun currentPosition(userId: String): String = Standard.currentPosition(userId)

        object Standard {
            fun balance(
                userId: String,
                accountType: String,
            ): String = "account:balance:user:$userId:account:$accountType"

            fun balanceLock(
                userId: String,
                accountType: String,
            ): String = "account:balance:user:$userId:account:$accountType:lock"

            fun availability(
                userId: String,
                mode: String,
            ): String = "account:availability:user:$userId:mode:$mode"

            fun currentPosition(userId: String): String = "account:position:current:user:$userId"
        }
    }

    object Market {
        fun quoteLast(code: String): String = Standard.quoteLast(code)

        fun orderbookTop(code: String): String = Standard.orderbookTop(code)

        fun candleCurrent(
            code: String,
            interval: String,
        ): String = Standard.candleCurrent(code, interval)

        fun candleToday1d(code: String): String = Standard.candleToday1d(code)

        fun candleToday1dPattern(): String = "market:candle:session:code:*:interval:1d"

        fun codeFromCandleToday1dKey(key: String): String? {
            val standardPrefix = "market:candle:session:code:"
            val standardSuffix = ":interval:1d"
            if (key.startsWith(standardPrefix) && key.endsWith(standardSuffix)) {
                return key.removePrefix(standardPrefix).removeSuffix(standardSuffix).takeIf { it.isNotBlank() }
            }

            val legacyPrefix = "candle:today:"
            val legacySuffix = ":1d"
            if (key.startsWith(legacyPrefix) && key.endsWith(legacySuffix)) {
                return key.removePrefix(legacyPrefix).removeSuffix(legacySuffix).takeIf { it.isNotBlank() }
            }
            return null
        }

        fun symbolMeta(code: String): String = Standard.symbolMeta(code)

        fun sessionCurrent(): String = Standard.sessionCurrent()

        fun candleBackfillMiss(
            code: String,
            interval: String,
        ): String = Standard.candleBackfillMiss(code, interval)

        fun candleBackfillUnrecoverable(
            code: String,
            interval: String,
        ): String = Standard.candleBackfillUnrecoverable(code, interval)

        fun candleUniverseDetailRecent(): String = Standard.candleUniverseDetailRecent()

        fun candleUniverseStrategyCandidates(): String = Standard.candleUniverseStrategyCandidates()

        object Standard {
            fun quoteLast(code: String): String = "market:quote:last:code:$code"

            fun orderbookTop(code: String): String = "market:orderbook:top:code:$code"

            fun candleCurrent(
                code: String,
                interval: String,
            ): String = "market:candle:current:code:$code:interval:$interval"

            fun candleToday1d(code: String): String = "market:candle:session:code:$code:interval:1d"

            fun symbolMeta(code: String): String = "market:symbol:meta:code:$code"

            fun sessionCurrent(): String = "market:session:current"

            fun candleBackfillMiss(
                code: String,
                interval: String,
            ): String = "market:candle:backfill:miss:code:$code:interval:$interval"

            fun candleBackfillUnrecoverable(
                code: String,
                interval: String,
            ): String = "market:candle:backfill:unrecoverable:code:$code:interval:$interval"

            fun candleUniverseDetailRecent(): String = "market:candle:universe:detail-recent"

            fun candleUniverseStrategyCandidates(): String = "market:candle:universe:strategy-candidates"
        }
    }

    object Strategy {
        fun activeByCode(
            code: String,
            interval: String,
        ): String = Standard.activeByCode(code, interval)

        fun activeByCodePattern(): String = "strategy:active:code:*:interval:*"

        object Standard {
            fun activeByCode(
                code: String,
                interval: String,
            ): String = "strategy:active:code:$code:interval:$interval"
        }
    }

    object Signal {
        fun dedupe(
            strategyId: Long,
            code: String,
            interval: String,
            tsEpochSeconds: Long,
        ): String = Standard.dedupe(strategyId, code, interval, tsEpochSeconds)

        object Standard {
            fun dedupe(
                strategyId: Long,
                code: String,
                interval: String,
                tsEpochSeconds: Long,
            ): String = "signal:dedupe:strategy:$strategyId:code:$code:interval:$interval:ts:$tsEpochSeconds"
        }
    }

    object Risk {
        fun tradeFrequency(
            strategyId: Long,
            userId: String,
            window: String,
        ): String = Standard.tradeFrequency(userId, strategyId, window)

        fun dailyPnl(
            userId: String,
            date: String,
        ): String = Standard.dailyPnl(userId, date)

        object Standard {
            fun tradeFrequency(
                userId: String,
                strategyId: Long,
                window: String,
            ): String = "risk:trade-frequency:user:$userId:strategy:$strategyId:window:$window"

            fun dailyPnl(
                userId: String,
                date: String,
            ): String = "risk:daily-pnl:user:$userId:date:$date"
        }
    }

    object Instrument {
        fun searchPrefix(prefix: String): String = "instrument:search:prefix:$prefix"

        fun searchPrefixPattern(): String = "instrument:search:prefix:*"

        fun byCode(code: String): String = Standard.byCode(code)

        fun byCodePattern(): String = "instrument:summary:code:*"

        fun list(market: String): String = Standard.list(market)

        fun listPattern(): String = "instrument:list:market:*"

        fun syncRunning(): String = Standard.syncRunning()

        fun syncLastAt(): String = Standard.syncLastAt()

        object Standard {
            fun searchPrefix(prefix: String): String = "instrument:search:prefix:$prefix"

            fun byCode(code: String): String = "instrument:summary:code:$code"

            fun list(market: String): String = "instrument:list:market:$market"

            fun syncRunning(): String = "instrument:sync:lock"

            fun syncLastAt(): String = "instrument:sync:last-at"
        }
    }

    object Indicator {
        fun resultPattern(code: String): String = "indicator:result:code:$code:*"

        fun result(
            code: String,
            interval: String,
            name: String,
            paramsHash: String,
        ): String = Standard.result(code, interval, name, paramsHash)

        fun resultWithRange(
            code: String,
            interval: String,
            name: String,
            paramsHash: String,
            rangeHash: String,
        ): String = Standard.resultWithRange(code, interval, name, paramsHash, rangeHash)

        object Standard {
            fun result(
                code: String,
                interval: String,
                name: String,
                paramsHash: String,
            ): String = "indicator:result:code:$code:interval:$interval:name:$name:params:$paramsHash"

            fun resultWithRange(
                code: String,
                interval: String,
                name: String,
                paramsHash: String,
                rangeHash: String,
            ): String = "indicator:result:code:$code:interval:$interval:name:$name:params:$paramsHash:range:$rangeHash"
        }
    }

    object Pattern {
        fun result(
            code: String,
            interval: String,
            patternsHash: String,
        ): String = Standard.result(code, interval, patternsHash)

        object Standard {
            fun result(
                code: String,
                interval: String,
                patternsHash: String,
            ): String = "pattern:result:code:$code:interval:$interval:set:$patternsHash"
        }
    }

    object Performance {
        fun summaryPattern(userId: String): String = "performance:summary:user:$userId:*"

        fun summary(
            userId: String,
            period: String,
            periodStart: String,
            periodEnd: String,
        ): String = Standard.summary(userId, period, periodStart, periodEnd)

        fun equityPattern(userId: String): String = "performance:equity:user:$userId:*"

        fun equity(
            userId: String,
            from: String,
            to: String,
        ): String = Standard.equity(userId, from, to)

        object Standard {
            fun summary(
                userId: String,
                period: String,
                periodStart: String,
                periodEnd: String,
            ): String = "performance:summary:user:$userId:period:$period:from:$periodStart:to:$periodEnd"

            fun equity(
                userId: String,
                from: String,
                to: String,
            ): String = "performance:equity:user:$userId:from:$from:to:$to"
        }
    }

    object Screener {
        fun lock(
            userId: String,
            strategyId: Long,
        ): String = Standard.lock(userId, strategyId)

        fun result(
            dslHash: String,
            universeFilterHash: String,
        ): String = Standard.result(dslHash, universeFilterHash)

        fun daytrade(
            preset: String,
            filterHash: String,
            interval: String,
            limit: Int,
            tradingDay: String,
        ): String = Standard.daytrade(preset, filterHash, interval, limit, tradingDay)

        object Standard {
            fun result(
                dslHash: String,
                universeFilterHash: String,
            ): String = "screener:result:dsl:$dslHash:universe:$universeFilterHash"

            fun lock(
                userId: String,
                strategyId: Long,
            ): String = "screener:run:user:$userId:strategy:$strategyId:lock"

            fun daytrade(
                preset: String,
                filterHash: String,
                interval: String,
                limit: Int,
                tradingDay: String,
            ): String = "screener:daytrade:preset:$preset:filter:$filterHash:interval:$interval:limit:$limit:date:$tradingDay"
        }
    }

    object News {
        fun naverRateLimit(): String = Standard.naverRateLimit()

        fun urlDedup(urlHash: String): String = Standard.urlDedup(urlHash)

        fun disclosureDedup(rceptNo: String): String = Standard.disclosureDedup(rceptNo)

        object Standard {
            fun naverRateLimit(): String = "news:ratelimit:provider:naver"

            fun urlDedup(urlHash: String): String = "news:dedup:url:sha256:$urlHash"

            fun disclosureDedup(rceptNo: String): String = "disclosure:dedup:rcept:$rceptNo"
        }
    }

    object Ai {
        fun promptCache(promptHash: String): String = Standard.promptCache(promptHash)

        object Standard {
            fun promptCache(promptHash: String): String = "ai:prompt:cache:sha256:$promptHash"
        }
    }

    object Notification {
        fun eventDedup(eventId: String): String = Standard.eventDedup(eventId)

        fun orderDedup(
            userId: String,
            orderId: String,
        ): String = Standard.orderDedup(userId, orderId)

        fun rateLimit(
            userId: String,
            topicHash: String,
        ): String = Standard.rateLimit(userId, topicHash)

        fun once(
            userId: String,
            topicHash: String,
        ): String = Standard.once(userId, topicHash)

        object Standard {
            fun eventDedup(eventId: String): String = "notification:dedup:event:$eventId"

            fun orderDedup(
                userId: String,
                orderId: String,
            ): String = "notification:dedup:order:user:$userId:order:$orderId"

            fun rateLimit(
                userId: String,
                topicHash: String,
            ): String = "notification:ratelimit:user:$userId:topic:$topicHash"

            fun once(
                userId: String,
                topicHash: String,
            ): String = "notification:once:user:$userId:topic:$topicHash"
        }
    }

    object Ops {
        fun realtimeStatus(): String = Standard.realtimeStatus()

        fun watchlistSource(userId: String): String = Standard.watchlistSource(userId)

        fun watchlistSourcePattern(): String = "ops:watchlist:source:user:*"

        object Standard {
            fun realtimeStatus(): String = "ops:realtime:status"

            fun watchlistSource(userId: String): String = "ops:watchlist:source:user:$userId"
        }
    }

    object Reconcile {
        fun lock(userId: String): String = Standard.lock(userId)

        fun lastRun(userId: String): String = Standard.lastRun(userId)

        object Standard {
            fun lock(userId: String): String = "reconcile:user:$userId:lock"

            fun lastRun(userId: String): String = "reconcile:user:$userId:last-run"
        }
    }
}
