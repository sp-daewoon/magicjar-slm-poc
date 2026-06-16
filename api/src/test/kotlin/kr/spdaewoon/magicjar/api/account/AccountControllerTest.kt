package kr.spdaewoon.magicjar.api.account

import kr.spdaewoon.magicjar.api.controller.account.AccountController
import kr.spdaewoon.magicjar.api.service.account.AccountQueryService
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimitTimeoutException
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class AccountControllerTest {
    private val accountQueryService: AccountQueryService = mock()
    private val sut = AccountController(accountQueryService)

    @Test
    fun `local KIS rate limiter timeout maps to 429`() {
        whenever(accountQueryService.getSummary("user001", AccountType.REAL, false))
            .thenThrow(KisRateLimitTimeoutException(KisCallPriority.USER_PATH, 3000L))

        val thrown = catchThrowable { sut.summary("user001", "REAL", false) }

        assertThat(thrown).isInstanceOf(ResponseStatusException::class.java)
        assertThat((thrown as ResponseStatusException).statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
    }
}
