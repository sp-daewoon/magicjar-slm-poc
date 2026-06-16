package kr.spdaewoon.magicjar.api.backtest

import jakarta.persistence.Column
import kr.spdaewoon.magicjar.api.persistence.backtest.BacktestRunJpaEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BacktestRunJpaEntityTest {
    @Test
    fun `backtest_run code column stores comma separated multi symbol universe`() {
        val column =
            BacktestRunJpaEntity::class.java
                .getDeclaredField("code")
                .getAnnotation(Column::class.java)

        assertNotNull(column)
        assertEquals(512, column.length)

        val migration =
            javaClass.classLoader
                .getResource("db/migration/V68__backtest_run_code_multi_symbol.sql")
                ?.readText()

        assertNotNull(migration)
        assertTrue(migration!!.contains("ALTER COLUMN code TYPE VARCHAR(512)"))
    }
}
