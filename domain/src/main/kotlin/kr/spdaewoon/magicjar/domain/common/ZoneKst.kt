package kr.spdaewoon.magicjar.domain.common

import kotlinx.datetime.TimeZone

/**
 * 한국 표준시 (Asia/Seoul).
 * KIS API 시각·DB 영속·로그 표현 공통 기준 (master-architecture §3-5 정합).
 */
object ZoneKst {
    val zone: TimeZone = TimeZone.of("Asia/Seoul")
}
