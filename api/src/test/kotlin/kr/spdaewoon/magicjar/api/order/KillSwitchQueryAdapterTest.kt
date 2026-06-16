package kr.spdaewoon.magicjar.api.order

import io.mockk.every
import io.mockk.mockk
import kr.spdaewoon.magicjar.api.persistence.order.KillSwitchQueryAdapter
import kr.spdaewoon.magicjar.api.persistence.order.KillSwitchQueryJpaRepository
import kr.spdaewoon.magicjar.api.persistence.order.KillSwitchReadEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

class KillSwitchQueryAdapterTest {
    private val repo = mockk<KillSwitchQueryJpaRepository>()
    private val adapter = KillSwitchQueryAdapter(repo)

    @Test
    fun `명시적 OFF row가 있으면 false를 반환한다`() {
        every { repo.findById("74683223") } returns Optional.of(KillSwitchReadEntity(userId = "74683223", enabled = false))

        assertThat(adapter.isOn("74683223")).isFalse()
    }

    @Test
    fun `row가 없으면 fail closed ON으로 반환한다`() {
        every { repo.findById("74683223") } returns Optional.empty()

        assertThat(adapter.isOn("74683223")).isTrue()
    }

    @Test
    fun `조회 예외가 나면 fail closed ON으로 반환한다`() {
        every { repo.findById("74683223") } throws IllegalStateException("db down")

        assertThat(adapter.isOn("74683223")).isTrue()
    }
}
