package kr.spdaewoon.magicjar.consumer.persistence

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

class ConsumerKillSwitchQueryAdapterTest {
    private val repo = mockk<KillSwitchJpaRepository>()
    private val adapter = ConsumerKillSwitchQueryAdapter(repo)

    @Test
    fun `명시적 OFF row가 있으면 false를 반환한다`() {
        every { repo.findById("74683223") } returns Optional.of(KillSwitchJpaEntity(userId = "74683223", enabled = false))

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
