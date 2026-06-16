package kr.spdaewoon.magicjar.domain.pattern

/**
 * 패턴 방향성 (KIS Strategy Builder candlestick.py 정합 — int 1/-1/0 ↔ enum BULL/BEAR/NEUTRAL).
 *
 * Source: candlestick.py L51 (`+1=bullish` / `-1=bearish` / `0=미감지`) ·
 *         pr-38-kis-pattern-raw-verification.md §6 LOCK · License: MIT
 *
 * - [BULL]: 상승 신호 (KIS +1)
 * - [BEAR]: 하락 신호 (KIS -1)
 * - [NEUTRAL]: 미감지 — 결과 미포함 (KIS 0)
 */
enum class Direction { BULL, BEAR, NEUTRAL }
