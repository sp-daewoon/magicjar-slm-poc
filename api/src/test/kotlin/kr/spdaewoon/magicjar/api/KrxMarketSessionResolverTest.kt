package kr.spdaewoon.magicjar.api

import kr.spdaewoon.magicjar.api.market.session.KrxMarketSessionResolver
import kr.spdaewoon.magicjar.domain.market.session.MarketCalendarEntry
import kr.spdaewoon.magicjar.domain.market.session.MarketSession
import kr.spdaewoon.magicjar.domain.market.session.port.MarketCalendarRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.datetime.Instant as KtInstant

/**
 * KrxMarketSessionResolver 단위 smoke 테스트 (PR-29a, 룰 4 정합).
 * ADR 0013 §2-1 시간 전이 검증 + 영업일/비영업일 분기 검증.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KrxMarketSessionResolverTest {
    @Mock
    private lateinit var marketCalendarRepository: MarketCalendarRepository

    @Mock
    private lateinit var redisTemplate: StringRedisTemplate

    @Mock
    private lateinit var valueOps: ValueOperations<String, String>

    private lateinit var resolver: KrxMarketSessionResolver

    private val kstZone = ZoneId.of("Asia/Seoul")

    @BeforeEach
    fun setUp() {
        `when`(redisTemplate.opsForValue()).thenReturn(valueOps)
        `when`(valueOps.get(org.mockito.kotlin.any())).thenReturn(null) // 캐시 미스
        resolver = KrxMarketSessionResolver(marketCalendarRepository, redisTemplate)
    }

    @Test
    fun `정규장 시간 09시 30분은 Regular 반환`() {
        // given: 2026-05-08(금) 09:30 KST — 영업일
        val date = LocalDate.of(2026, 5, 8)
        val instant = date.atTime(9, 30).atZone(kstZone).toInstant()
        val ktInstant = KtInstant.fromEpochMilliseconds(instant.toEpochMilli())

        `when`(marketCalendarRepository.findByDate(date)).thenReturn(
            MarketCalendarEntry(date = date, isBusinessDay = true, isHoliday = false, holidayName = null, source = "STATIC_SEED"),
        )

        // when
        val session = resolver.resolve(ktInstant)

        // then
        assertThat(session).isInstanceOf(MarketSession.Regular::class.java)
    }

    @Test
    fun `장전 시간외 08시 30분은 PreMarket 반환`() {
        // given: 2026-05-08(금) 08:30 KST — 영업일
        val date = LocalDate.of(2026, 5, 8)
        val instant = date.atTime(8, 30).atZone(kstZone).toInstant()
        val ktInstant = KtInstant.fromEpochMilliseconds(instant.toEpochMilli())

        `when`(marketCalendarRepository.findByDate(date)).thenReturn(
            MarketCalendarEntry(date = date, isBusinessDay = true, isHoliday = false, holidayName = null, source = "STATIC_SEED"),
        )

        // when
        val session = resolver.resolve(ktInstant)

        // then
        assertThat(session).isInstanceOf(MarketSession.PreMarket::class.java)
    }

    @Test
    fun `장후 시간외 15시 45분은 PostMarket 반환`() {
        // given: 2026-05-08(금) 15:45 KST — 영업일
        val date = LocalDate.of(2026, 5, 8)
        val instant = date.atTime(15, 45).atZone(kstZone).toInstant()
        val ktInstant = KtInstant.fromEpochMilliseconds(instant.toEpochMilli())

        `when`(marketCalendarRepository.findByDate(date)).thenReturn(
            MarketCalendarEntry(date = date, isBusinessDay = true, isHoliday = false, holidayName = null, source = "STATIC_SEED"),
        )

        // when
        val session = resolver.resolve(ktInstant)

        // then
        assertThat(session).isInstanceOf(MarketSession.PostMarket::class.java)
    }

    @Test
    fun `공휴일은 Closed 반환`() {
        // given: 2026-01-01(신정) 10:00 KST
        val date = LocalDate.of(2026, 1, 1)
        val instant = date.atTime(10, 0).atZone(kstZone).toInstant()
        val ktInstant = KtInstant.fromEpochMilliseconds(instant.toEpochMilli())

        `when`(marketCalendarRepository.findByDate(date)).thenReturn(
            MarketCalendarEntry(date = date, isBusinessDay = false, isHoliday = true, holidayName = "신정", source = "STATIC_SEED"),
        )

        // when
        val session = resolver.resolve(ktInstant)

        // then
        assertThat(session).isInstanceOf(MarketSession.Closed::class.java)
        assertThat((session as MarketSession.Closed).reason).contains("신정")
    }

    @Test
    fun `주말은 DB 미등록 시에도 Closed 반환 (주말 fallback)`() {
        // given: 2026-05-09(토) 11:00 KST — DB 미등록
        val date = LocalDate.of(2026, 5, 9) // 토요일
        val instant = date.atTime(11, 0).atZone(kstZone).toInstant()
        val ktInstant = KtInstant.fromEpochMilliseconds(instant.toEpochMilli())

        `when`(marketCalendarRepository.findByDate(date)).thenReturn(null) // DB 미등록

        // when
        val session = resolver.resolve(ktInstant)

        // then
        assertThat(session).isInstanceOf(MarketSession.Closed::class.java)
        assertThat((session as MarketSession.Closed).reason).isEqualTo("주말")
    }

    @Test
    fun `isBusinessDay 영업일 true 반환`() {
        // given
        val date = LocalDate.of(2026, 5, 8) // 금요일
        `when`(marketCalendarRepository.findByDate(date)).thenReturn(
            MarketCalendarEntry(date = date, isBusinessDay = true, isHoliday = false, holidayName = null, source = "STATIC_SEED"),
        )

        // when + then
        assertThat(resolver.isBusinessDay(date)).isTrue()
    }

    @Test
    fun `nextBusinessDay 주말 건너뛰기`() {
        // given: 금요일 → 다음 월요일
        val friday = LocalDate.of(2026, 5, 8)
        val monday = LocalDate.of(2026, 5, 11)

        `when`(marketCalendarRepository.findByDate(org.mockito.kotlin.any())).thenAnswer { invocation ->
            val d = invocation.getArgument<LocalDate>(0)
            val isWeekend = d.dayOfWeek == java.time.DayOfWeek.SATURDAY || d.dayOfWeek == java.time.DayOfWeek.SUNDAY
            if (isWeekend) {
                null
            } else {
                MarketCalendarEntry(
                    date = d,
                    isBusinessDay = true,
                    isHoliday = false,
                    holidayName = null,
                    source = "STATIC_SEED",
                )
            }
        }

        // when
        val next = resolver.nextBusinessDay(friday)

        // then
        assertThat(next).isEqualTo(monday)
    }
}
