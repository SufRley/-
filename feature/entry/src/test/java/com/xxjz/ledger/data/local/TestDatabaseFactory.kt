package com.xxjz.ledger.data.local

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.xxjz.ledger.data.local.db.AppDatabase
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executor

/**
 * 测试用数据库工厂。
 *
 * DAO 测试跑在 Robolectric 沙箱里（`@RunWith(RobolectricTestRunner::class)`），由 Robolectric 提供
 * 宿主平台真实可用的 SQLite 引擎，因此这里不注入任何自定义驱动，直接使用 Room 默认的框架 SQLite。
 * 这样 DAO 的 SQL（聚合、索引、软删除过滤）都在真实 SQLite 上执行，测试有效性不受影响。
 *
 * `setQueryCoroutineContext` 把 suspend 查询收在**同步执行器**上：Room 默认会为每个 suspend
 * 查询建线程池，在 `runTest` 里会出现「任务还挂在真实线程池上，`advanceUntilIdle()` 已经返回」，
 * 断言因此读到中间态。改成同步执行器后，查询调用即完成，ViewModel 的状态流转可以稳定断言。
 *
 * 不能直接用 `Dispatchers.Unconfined`：Room 初始化时会调 `limitedParallelism`，Unconfined 不支持。
 */
object TestDatabaseFactory {
    /** 调用线程直接执行，不排队、不切线程。 */
    private val SYNC_EXECUTOR = Executor { it.run() }
    private val SYNC_DISPATCHER = SYNC_EXECUTOR.asCoroutineDispatcher()

    fun create(): AppDatabase =
        Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries()
            .setQueryCoroutineContext(SYNC_DISPATCHER)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .build()
}
