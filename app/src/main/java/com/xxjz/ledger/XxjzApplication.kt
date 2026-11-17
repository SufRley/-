package com.xxjz.ledger

import android.app.Application
import com.xxjz.ledger.core.common.Logger
import com.xxjz.ledger.data.local.db.AppDatabase
import com.xxjz.ledger.data.local.seed.Seeder
import com.xxjz.ledger.data.repository.DebtToAccountMigrator
import com.xxjz.ledger.data.worker.DailyReminderWorker
import com.xxjz.ledger.data.worker.RecurringWorker
import com.xxjz.ledger.data.worker.TrashCleanupWorker
import com.xxjz.ledger.domain.repository.SettingsRepository
import com.xxjz.ledger.domain.usecase.RunInstallmentUseCase
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
    lateinit var runInstallment: RunInstallmentUseCase

    @Inject
    lateinit var trashCleanup: TrashCleanupUseCase

    @Inject
    lateinit var settings: SettingsRepository

    @Inject
    lateinit var debtMigrator: DebtToAccountMigrator

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // core 模块不能反向依赖 app 读 BuildConfig，日志开关与版本号在这里从 app 层注入。
        Logger.enabled = BuildConfig.DEBUG
        com.xxjz.ledger.data.backup.BackupPayloadBuilder.APP_VERSION = BuildConfig.VERSION_NAME
        // 首启写入预置分类 / 默认账户 / 默认账本（幂等，见 Seeder）；
        // 随后把 v1.8.x 的旧借贷台账搬成借贷账户（v1.9.0，幂等，debt 表空了就跳过）
        scope.launch {
            runCatching {
                Seeder.seedIfNeeded(database)
                debtMigrator.run()
            }
        }
        // 启动补执行 + 每日后台任务双保险（AC-52 / AC-58）。引擎幂等，重复触发不会重复记账。
        scope.launch { runCatching { runRecurring() } }
        // 分期每月出账（v1.9.5 第 9 条）：与周期记账同款幂等引擎，启动与每日后台各补一次
        scope.launch { runCatching { runInstallment() } }
        runCatching { RecurringWorker.schedule(this) }
        // 回收站到期清理（AC-76）：启动时清一次 + 每日后台兜底；保留天数为「永久」时内部直接跳过
        scope.launch { runCatching { trashCleanup() } }
        runCatching { TrashCleanupWorker.schedule(this) }
        // 每日记账提醒（AC-85）：每次冷启动按当前设置重排一次，用户改过时间也能立刻生效
        runCatching { DailyReminderWorker.ensureChannel(this) }
        scope.launch {
            runCatching {
                val prefs = settings.current()
                DailyReminderWorker.schedule(this@XxjzApplication, prefs.dailyReminderMinute.takeIf { prefs.dailyReminderEnabled })
            }
        }
    }
}
