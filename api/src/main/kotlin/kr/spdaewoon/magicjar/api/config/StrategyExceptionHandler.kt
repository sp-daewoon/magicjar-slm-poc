package kr.spdaewoon.magicjar.api.config

import kr.spdaewoon.magicjar.domain.strategy.StrategyException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * 전략 DSL 예외 전역 핸들러 — StrategyException sealed class → HTTP 400 매핑.
 *
 * ADR 0047 §2-1 정합:
 * - DSL 파싱·검증 예외 → 400 Bad Request + error/detail JSON
 * - StrategyTimeoutException → 내부 처리 (auto-disable 후 log) → 여기서는 500 대신 빈 응답 방어
 */
@RestControllerAdvice
class StrategyExceptionHandler {
    private val log = LoggerFactory.getLogger(StrategyExceptionHandler::class.java)

    @ExceptionHandler(StrategyException.DslSyntaxException::class)
    fun handleDslSyntax(ex: StrategyException.DslSyntaxException): ResponseEntity<Map<String, String>> {
        log.warn("DSL syntax error: {}", ex.message)
        return ResponseEntity.badRequest().body(mapOf("error" to "DslSyntaxException", "detail" to (ex.message ?: "")))
    }

    @ExceptionHandler(StrategyException.DslDepthExceededException::class)
    fun handleDepth(ex: StrategyException.DslDepthExceededException): ResponseEntity<Map<String, String>> {
        log.warn("DSL depth exceeded: {}", ex.message)
        return ResponseEntity.badRequest().body(mapOf("error" to "DslDepthExceededException", "detail" to (ex.message ?: "")))
    }

    @ExceptionHandler(StrategyException.DslNodeCountExceededException::class)
    fun handleNodeCount(ex: StrategyException.DslNodeCountExceededException): ResponseEntity<Map<String, String>> {
        log.warn("DSL node count exceeded: {}", ex.message)
        return ResponseEntity.badRequest().body(mapOf("error" to "DslNodeCountExceededException", "detail" to (ex.message ?: "")))
    }

    @ExceptionHandler(StrategyException.DslIndicatorCallExceededException::class)
    fun handleIndicatorCall(ex: StrategyException.DslIndicatorCallExceededException): ResponseEntity<Map<String, String>> {
        log.warn("DSL indicator call exceeded: {}", ex.message)
        return ResponseEntity.badRequest().body(mapOf("error" to "DslIndicatorCallExceededException", "detail" to (ex.message ?: "")))
    }

    @ExceptionHandler(StrategyException.UnknownDslSymbolException::class)
    fun handleUnknownSymbol(ex: StrategyException.UnknownDslSymbolException): ResponseEntity<Map<String, String>> {
        log.warn("Unknown DSL symbol: {}", ex.message)
        return ResponseEntity.badRequest().body(mapOf("error" to "UnknownDslSymbolException", "detail" to (ex.message ?: "")))
    }

    @ExceptionHandler(StrategyException.InsufficientCandlesException::class)
    fun handleInsufficientCandles(ex: StrategyException.InsufficientCandlesException): ResponseEntity<Map<String, String>> {
        log.warn("Insufficient candles: {}", ex.message)
        return ResponseEntity.badRequest().body(mapOf("error" to "InsufficientCandlesException", "detail" to (ex.message ?: "")))
    }

    @ExceptionHandler(StrategyException.StrategyTimeoutException::class)
    fun handleTimeout(ex: StrategyException.StrategyTimeoutException): ResponseEntity<Map<String, String>> {
        log.error("Strategy evaluation timeout — auto-disable triggered: {}", ex.message)
        return ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(mapOf("error" to "StrategyTimeoutException", "detail" to (ex.message ?: "")))
    }
}
