package kr.spdaewoon.magicjar.api.service.backtest

import kr.spdaewoon.magicjar.domain.backtest.port.BacktestCommissionModel
import kr.spdaewoon.magicjar.domain.backtest.port.BacktestSlippageModel
import kr.spdaewoon.magicjar.domain.backtest.port.BacktestTaxModel
import org.springframework.stereotype.Component

/**
 * 백테스트 모델 레지스트리 (ADR 0056 §2-1).
 *
 * BacktestRequest의 taxModel·commissionModel·slippageModel 문자열 키 →
 * 해당 구현체를 lookup하는 단순 위임 레지스트리.
 *
 * ### 등록된 모델 (ADR 0056 §2-3-B)
 * - Tax: "korean_default", "none"
 * - Commission: "kis_direct", "none"
 * - Slippage: "fixed_0.05", "none"
 */
@Component
class BacktestModelRegistry(
    taxModels: List<BacktestTaxModel>,
    commissionModels: List<BacktestCommissionModel>,
    slippageModels: List<BacktestSlippageModel>,
) {
    private val taxMap: Map<String, BacktestTaxModel> = taxModels.associateBy { it.name }
    private val commissionMap: Map<String, BacktestCommissionModel> = commissionModels.associateBy { it.name }
    private val slippageMap: Map<String, BacktestSlippageModel> = slippageModels.associateBy { it.name }

    /**
     * 세금 모델 반환. 미등록 키이면 IllegalArgumentException.
     */
    fun taxModel(key: String): BacktestTaxModel =
        taxMap[key]
            ?: throw IllegalArgumentException(
                "등록되지 않은 세금 모델 키: '$key'. 허용 키: ${taxMap.keys}",
            )

    /**
     * 수수료 모델 반환. 미등록 키이면 IllegalArgumentException.
     */
    fun commissionModel(key: String): BacktestCommissionModel =
        commissionMap[key]
            ?: throw IllegalArgumentException(
                "등록되지 않은 수수료 모델 키: '$key'. 허용 키: ${commissionMap.keys}",
            )

    /**
     * 슬리피지 모델 반환. 미등록 키이면 IllegalArgumentException.
     */
    fun slippageModel(key: String): BacktestSlippageModel =
        slippageMap[key]
            ?: throw IllegalArgumentException(
                "등록되지 않은 슬리피지 모델 키: '$key'. 허용 키: ${slippageMap.keys}",
            )
}
