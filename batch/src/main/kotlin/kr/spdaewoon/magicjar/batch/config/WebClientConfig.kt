package kr.spdaewoon.magicjar.batch.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient

/**
 * Batch WebClient Bean 설정.
 *
 * 빈 구조:
 *  - webClient (기본) — KIS·Naver 등 일반 endpoint. JDK 기본 SSL context.
 *
 * [변경 이력 — hotfix/dart-ssl-handshake]
 * 구: dartWebClient (DART 전용 Reactor Netty + SslProvider.JDK) — 제거.
 *   Reactor Netty SslContextBuilder 가 enabled cipher list를 제한하여
 *   opendart.fss.or.kr (TLS_DHE_RSA_WITH_AES_128_CBC_SHA256) 와 cipher 협상 불가.
 *   → handshake_failure (backlog §22 fix 적용 후에도 동일 결함 재현).
 * 신: DartCorpCodeRepository·DartDisclosureAdapter 가 java.net.http.HttpClient 직접 사용.
 *   JDK 21 TLS 스택 직접 사용 → 동일 컨테이너 환경에서 wget 과 동일하게 성공 확인.
 *   출처: https://opendart.fss.or.kr/api/corpCode.xml (ADR 0018 §4-1)
 */
@Configuration
class WebClientConfig {
    /** 일반 WebClient — KIS·Naver 등 (JDK 기본 SSL context) */
    @Bean
    fun webClient(): WebClient = WebClient.builder().build()
}
