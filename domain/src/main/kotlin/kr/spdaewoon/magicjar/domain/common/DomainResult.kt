package kr.spdaewoon.magicjar.domain.common

/**
 * 도메인 연산 결과 sealed class.
 * Spring 의존 없이 포트 인터페이스 반환 타입으로 사용.
 */
sealed class DomainResult<out T> {
    data class Success<T>(
        val value: T,
    ) : DomainResult<T>()

    data class Failure(
        val reason: String,
        val cause: Throwable? = null,
    ) : DomainResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    fun getOrNull(): T? =
        when (this) {
            is Success -> value
            is Failure -> null
        }

    fun getOrThrow(): T =
        when (this) {
            is Success -> value
            is Failure -> throw IllegalStateException(reason, cause)
        }
}
