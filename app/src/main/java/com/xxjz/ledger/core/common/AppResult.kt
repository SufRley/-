package com.xxjz.ledger.core.common

/** 业务失败原因。 */
sealed class AppError(open val message: String) {
    data object AmountNotPositive : AppError("金额必须大于 0")

    data object AmountTooLarge : AppError("金额超出 9 位上限")

    data object CategoryRequired : AppError("请选择分类")

    data object AccountRequired : AppError("请选择账户")

    data object SameAccount : AppError("转出和转入账户不能相同")

    data object DebtCounterpartyRequired : AppError("请填写对方")

    data object RefundExceedsOriginal : AppError("退款金额不能超过原支出未退金额")

    data object NotFound : AppError("记录不存在")

    data object NameRequired : AppError("名称不能为空")

    data class Custom(override val message: String) : AppError(message)
}

/** 轻量业务结果类型，避免到处抛异常。 */
sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>

    data class Failure(val error: AppError) : AppResult<Nothing>
}

inline fun <T> AppResult<T>.onSuccess(block: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) block(data)
    return this
}

inline fun <T> AppResult<T>.onFailure(block: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Failure) block(error)
    return this
}

fun AppResult<*>.errorOrNull(): AppError? = (this as? AppResult.Failure)?.error
