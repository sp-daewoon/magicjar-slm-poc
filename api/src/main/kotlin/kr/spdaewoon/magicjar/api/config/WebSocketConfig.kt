package kr.spdaewoon.magicjar.api.config

import org.springframework.context.annotation.Configuration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer

/**
 * STOMP WebSocket 브로커 설정 (ADR 0029 §2-5 — /topic/ 매트릭스 첫 발행).
 *
 * 1차 MVP: simple broker (in-memory).
 * post-MVP: relay broker (ActiveMQ·RabbitMQ) 전환 가능.
 *
 * STOMP topic 매트릭스 (spec §3-5 LOCK):
 * - /topic/market.tick.{code}           — 현재가 tick (MarketTickStompForwarder)
 * - /topic/market.candle.minute.{code}  — 1m closing 캔들 (MarketCandleStompForwarder)
 * - /topic/market.orderbook.{code}      — 호가 스냅샷 (MarketOrderbookStompForwarder)
 *
 * WS endpoint: /ws (SockJS 미사용 — 브라우저 native WS)
 * CORS: allowedOriginPatterns * (dev 1차 — post-MVP 도메인 제한)
 */
@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig : WebSocketMessageBrokerConfigurer {
    override fun configureMessageBroker(registry: MessageBrokerRegistry) {
        registry.enableSimpleBroker("/topic")
        registry.setApplicationDestinationPrefixes("/app")
    }

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*")
    }
}
