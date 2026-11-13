package com.xxjz.ledger

import android.app.Application
import com.xxjz.ledger.data.local.db.AppDatabase
import com.xxjz.ledger.data.local.seed.Seeder
import com.xxjz.ledger.data.worker.RecurringWorker
import com.xxjz.ledger.data.worker.TrashCleanupWorker
import com.xxjz.ledger.domain.usecase.RunRecurringUseCase
import com.xxjz.ledger.domain.usecase.TrashCleanupUseCase
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 应用入口，Hilt 的依赖注入根。 */
@HiltAndroidApp
class XxjzApplication : Application() {
    @Inject
    lateinit var database: AppDatabase

    @Inject
    lateinit var runRecurring: RunRecurringUseCase

    @Inject
    lateinit var trashCleanup: TrashCleanupUseCase

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 首启写入预置分类 / 默认账户 / 默认账本（幂等，见 Seeder）
        scope.launch { runCatching { Seeder.seedIfNeeded(database) } }
        // 启动补执行 + 每日后台任务双保险（AC-52 / AC-58）。引擎幂等，重复触发不会重复记账。
        scope.launch { runCatching { runRecurring() } }
        runCatching { RecurringWorker.schedule(this) }
        // 回收站到期清理（AC-76）：启动时清一次 + 每日后台兜底；保留天数为「永久」时内部直接跳过
        scope.launch { runCatching { trashCleanup() } }
        runCatching { TrashCleanupWorker.schedule(this) }
    }
}
