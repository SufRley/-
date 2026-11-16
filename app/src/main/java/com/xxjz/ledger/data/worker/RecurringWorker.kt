package com.xxjz.ledger.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xxjz.ledger.domain.usecase.RunInstallmentUseCase
import com.xxjz.ledger.domain.usecase.RunRecurringUseCase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/** 从 WorkManager 里取依赖：不改造 HiltWorkerFactory，避免动 Application 配置。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RecurringWorkerEntryPoint {
    fun runRecurring(): RunRecurringUseCase

    fun runInstallment(): RunInstallmentUseCase
}

/**
 * 周期记账的每日后台任务（AC-52 的另一半保险）。
 *
 * 与「App 启动补执行」并存：启动时补一次，后台每天补一次；两边都调用同一个幂等引擎，
 * 重复触发也不会重复记账（AC-58）。
 */
class RecurringWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val entry = EntryPointAccessors.fromApplication(applicationContext, RecurringWorkerEntryPoint::class.java)
        // 两件事一起做：周期规则补记 + 分期补出账（v1.9.5 第 9 条）。
        // 各自幂等，任一失败只影响自己，整轮仍按失败重试。
        return runCatching {
            entry.runRecurring()()
            entry.runInstallment()()
        }.fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }

    companion object {
        const val UNIQUE_NAME = "recurring_daily"

        /** 注册每日后台任务；已存在则保留（KEEP），不重置周期。 */
        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<RecurringWorker>(1, TimeUnit.DAYS)
                    .setInitialDelay(5, TimeUnit.MINUTES)
                    .build()
            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
