package kr.spdaewoon.magicjar.batch.job.instrument

import java.time.Duration
import java.time.Instant

/**
 * 종목 마스터 sync 결과 DTO (T-IM1 PR-11a).
 */
data class InstrumentSyncResult(
    val cronTag: String,
    val fetched: Int,
    val inserted: Int,
    val updated: Int,
    val delisted: Int,
    val failed: Int,
    val startedAt: Instant,
    val finishedAt: Instant,
) {
    val elapsed: Duration get() = Duration.between(startedAt, finishedAt)

    override fun toString(): String =
        "[instrument-sync] cron=$cronTag fetched=$fetched inserted=$inserted " +
            "updated=$updated delisted=$delisted failed=$failed " +
            "elapsed=${elapsed.toSeconds()}s"
}
