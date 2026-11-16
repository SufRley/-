package com.xxjz.ledger.data.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xxjz.ledger.MainActivity
import com.xxjz.ledger.R
import com.xxjz.ledger.core.common.DateUtils
import com.xxjz.ledger.core.common.ReminderMessage
import com.xxjz.ledger.core.common.ReminderPlanner
import com.xxjz.ledger.domain.repository.SettingsRepository
import com.xxjz.ledger.domain.repository.TxnRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Duration
import java.util.concurrent.TimeUnit

/** 与其它 Worker 一致：EntryPoint 取依赖。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderEntryPoint {
    fun settings(): SettingsRepository

    fun txnRepository(): TxnRepository
}

/**
 * 每日记账提醒（AC-85）。
 *
 * 用「一次性任务 + 每次算下一次延迟」而不是 `PeriodicWorkRequest(1, DAYS)`：
 * 周期任务的首次触发时间由 WorkManager 自己排，用户改了提醒时间对不上；
 * 自己算 `initialDelay` 才能保证「我设的是 21:00，就该 21:00 响」（误差受系统省电策略影响）。
 */
class DailyReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val entry = EntryPointAccessors.fromApplication(applicationContext, ReminderEntryPoint::class.java)
        val prefs = entry.settings().current()
        val today = DateUtils.today()
        val dateKey = DateUtils.dateKeyOf(today)
        val range = DateUtils.monthRange(today.year, today.monthValue)

        // 今天已经记过账就不打扰；开关关掉更不打扰。
        val todayTxns = entry.txnRepository().listInRange(range.first, range.last).filter { it.dateKey == dateKey }
        if (ReminderMessage.shouldNotify(prefs.dailyReminderEnabled, todayTxns, dateKey)) {
            notify(applicationContext)
        }
        // 无论发没发，都要排下一轮，否则用户第二天就收不到了。
        schedule(applicationContext, prefs.dailyReminderMinute)
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "daily_reminder"
        const val CHANNEL_ID = "daily_reminder"
        const val NOTIFICATION_ID = 1001

        /** 建渠道（幂等）。Android 8+ 必须有渠道，否则通知静默丢弃。 */
        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.reminder_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.reminder_channel_description)
                    setShowBadge(true)
                }
            manager.createNotificationChannel(channel)
        }

        fun canPost(context: Context): Boolean =
            android.os.Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

        fun notify(context: Context) {
            if (!canPost(context)) return
            ensureChannel(context)
            val open =
                PendingIntent.getActivity(
                    context,
                    0,
                    android.content.Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(ReminderMessage.TITLE)
                    .setContentText(ReminderMessage.BODY)
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build()
            runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
        }

        /**
         * 排下一次提醒；[minuteOfDay] 为 null 表示关闭提醒（取消已排队任务）。
         *
         * 用 REPLACE：用户改了提醒时间就该按新时间走，不像回收站清理那样保留旧队列。
         */
        fun schedule(
            context: Context,
            minuteOfDay: Int?,
        ) {
            val manager = WorkManager.getInstance(context)
            if (minuteOfDay == null) {
                manager.cancelUniqueWork(UNIQUE_NAME)
                return
            }
            val plan = ReminderPlanner.next(System.currentTimeMillis(), enabled = true, minuteOfDay = minuteOfDay)
            val delay = (plan.nextAt - System.currentTimeMillis()).coerceAtLeast(0)
            val request =
                OneTimeWorkRequestBuilder<DailyReminderWorker>()
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .setConstraints(Constraints.NONE)
                    .build()
            manager.enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /** 距离下一次提醒还有多久（人话版），设置页用来提示「X 小时 Y 分钟后提醒」。 */
        fun humanizeUntilNext(
            now: Long,
            minuteOfDay: Int,
        ): String {
            val plan = ReminderPlanner.next(now, enabled = true, minuteOfDay)
            val minutes = Duration.ofMillis(plan.nextAt - now).toMinutes().coerceAtLeast(0)
            return if (minutes >= 60) "${minutes / 60} 小时 ${minutes % 60} 分钟后提醒" else "$minutes 分钟后提醒"
        }
    }
}
