package kr.spdaewoon.magicjar.consumer.config

import kr.spdaewoon.magicjar.consumer.infrastructure.observability.TraceIdWebClientFilter
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import reactor.netty.http.client.HttpClient

/**
 * Consumer WebClient / WebSocketClient Bean 설정.
 *
 * 빈 분리 구조:
 *  - webClient (기본) — KIS REST 등 일반 endpoint.
 *  - kisWebSocketClient (KIS WS 전용) — ws:// (비암호화 WebSocket) 전용 클라이언트.
 *
 * KIS WS 서버는 ws:// (비암호화) 사용.
 * KIS-first 출처: kis_devlp.yaml ops="ws://ops.koreainvestment.com:21000" (SSL 없음)
 * https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/kis_devlp.yaml
 *
 * 주의: wss:// 사용 시 SSL handshake timeout 발생 (SslHandshakeTimeoutException) →
 *       서버가 ClientHello에 응답 않고 10초 후 timeout. NotSslRecordException 후속 발생.
 *       (이전 ws.url-prod=wss:// 기본값 결함 — 본 PR에서 ws://로 정정)
 */
@Configuration
class WebClientConfig {
    /**
     * 일반 WebClient — KIS REST 호출.
     *
     * ### MDC traceId 자동 부착 (ADR 0084 P1-3)
     * [TraceIdWebClientFilter] 등록 — 모든 outbound HTTP 호출 헤더에 진입 Kafka record의 traceId 부착.
     * KIS REST audit log chain 추적 가능.
     */
    @Bean
    fun webClient(traceIdWebClientFilter: TraceIdWebClientFilter): WebClient =
        WebClient
            .builder()
            .filter(traceIdWebClientFilter)
            .build()

    /**
     * KIS WS 전용 WebSocketClient — ws:// 비암호화 WebSocket.
     *
     * KIS WS 서버(ops.koreainvestment.com:21000)는 SSL 없는 ws:// 프로토콜 사용.
     * KIS-first 출처: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/kis_devlp.yaml
     *   ops: "ws://ops.koreainvestment.com:21000"
     *   vops: "ws://ops.koreainvestment.com:31000"
     *
     * ReactorNettyWebSocketClient 빈 분리 이유:
     * - 향후 ws:// 전용 connection pool 또는 타임아웃 설정 분리 가능
     * - KisWebSocketBridge @Qualifier 주입으로 SSL 미설정 HttpClient 보장
     */
    @Bean
    @Qualifier("kisWebSocketClient")
    fun kisWebSocketClient(): ReactorNettyWebSocketClient {
        // KIS WS는 ws:// — SSL context 없이 일반 HttpClient 사용
        val httpClient = HttpClient.create()
        return ReactorNettyWebSocketClient(httpClient)
    }
}
