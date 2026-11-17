package com.xxjz.ledger.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xxjz.ledger.domain.usecase.TrashCleanupUseCase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/** 与 [RecurringWorker] 一致的取依赖方式：不去改造 HiltWorkerFactory。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TrashCleanupEntryPoint {
    fun trashCleanup(): TrashCleanupUseCase
}

/**
 * 回收站清理（AC-76）：每天跑一次，把超过保留天数的软删记录物理删除。
 *
 * 用 KEEP 而不是 REPLACE：每次冷启动都会注册一遍，不该把已排好的队列重置。
 */
class TrashCleanupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val cleanup =
            EntryPointAccessors
                .fromApplication(applicationContext, TrashCleanupEntryPoint::class.java)
                .trashCleanup()
        return runCatching { cleanup() }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
    }

    companion object {
        const val UNIQUE_NAME = "trash_cleanup_daily"

        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<TrashCleanupWorker>(1, TimeUnit.DAYS)
                    .setInitialDelay(1, TimeUnit.HOURS)
                    .build()
            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
