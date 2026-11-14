package com.xxjz.ledger.feature.backup

import com.xxjz.ledger.data.backup.BackupCodec
import com.xxjz.ledger.data.backup.BackupPayload
import com.xxjz.ledger.data.backup.TxnDto
import com.xxjz.ledger.data.datastore.AppSettings
import com.xxjz.ledger.data.datastore.SettingsDataStore
import com.xxjz.ledger.data.local.TestDatabaseFactory
import com.xxjz.ledger.data.local.db.AppDatabase
import com.xxjz.ledger.data.repository.DebtToAccountMigrator
import com.xxjz.ledger.domain.usecase.RestoreBackupUseCase
import com.xxjz.ledger.testutil.FakeDocumentIo
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** M8 导入流程（AC-71、AC-72、AC-73）：解密预览、密码错不崩、覆盖确认后才动数据。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RestoreViewModelTest {
    private val codec = BackupCodec()
    private val io = FakeDocumentIo()

    private lateinit var db: AppDatabase
    private lateinit var viewModel: RestoreViewModel

    @Before
    fun setUp() {
        db = TestDatabaseFactory.create()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = RestoreViewModel(codec, io, RestoreBackupUseCase(db, mockk(relaxed = true), DebtToAccountMigrator(db)))
        viewModel.ioDispatcher = UnconfinedTestDispatcher()
    }

    @After
    fun tearDown() {
        if (this::db.isInitialized) db.close()
        Dispatchers.resetMain()
    }

    private fun putBackup(
        password: String = "abc123",
        payload: BackupPayload = sample(),
    ): String {
        val uri = "content://test/backup.xxjzbak"
        io.files[uri] = codec.encode(payload, password)
        io.displayNameOverride = "backup.xxjzbak"
        return uri
    }

    private fun sample() =
        BackupPayload(
            schemaVersion = AppDatabase.SCHEMA_VERSION,
            createdAt = 1_700_000_000_000,
            accounts = listOf(com.xxjz.ledger.data.backup.AccountDto(id = "a1", name = "现金")),
            categories = listOf(com.xxjz.ledger.data.backup.CategoryDto(id = "c1", name = "餐饮")),
            txns = listOf(TxnDto(id = "t1", amount = 500), TxnDto(id = "t2", amount = 600)),
        )

    @Test
    fun selectFile() =
        runTest {
            val uri = putBackup()
            viewModel.selectFile(uri)
            assertEquals("backup.xxjzbak", viewModel.state.value.fileName)

            viewModel.decryptPreview()
            assertEquals("请输入备份密码", viewModel.state.value.error)
            assertNull(viewModel.state.value.preview)
        }

    @Test
    fun previewOk() =
        runTest {
            viewModel.selectFile(putBackup())
            viewModel.setPassword("abc123")
            viewModel.decryptPreview()

            val s = viewModel.state.value
            assertNull(s.error)
            assertFalse(s.decrypting)
            assertEquals(2, s.stats?.txnCount)
            assertEquals(1, s.stats?.accountCount)
            assertEquals(1_700_000_000_000, s.stats?.createdAt)
        }

    @Test
    fun wrongPassword() =
        runTest {
            viewModel.selectFile(putBackup())
            viewModel.setPassword("wrong-pass")
            viewModel.decryptPreview()

            val s = viewModel.state.value
            assertEquals("密码错误，无法解密这份备份", s.error)
            assertNull(s.preview)
        }

    @Test
    fun notBackupFile() =
        runTest {
            val uri = "content://test/photo.jpg"
            io.files[uri] = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0, 1, 2)
            viewModel.selectFile(uri)
            viewModel.setPassword("abc123")
            viewModel.decryptPreview()
            assertEquals("这不是「一心记账」的备份文件", viewModel.state.value.error)
        }

    @Test
    fun newerVersion() =
        runTest {
            viewModel.selectFile(putBackup(payload = sample().copy(schemaVersion = AppDatabase.SCHEMA_VERSION + 1)))
            viewModel.setPassword("abc123")
            viewModel.decryptPreview()
            assertTrue(viewModel.state.value.error!!.contains("更高") || viewModel.state.value.error!!.contains("升级"))
        }

    @Test
    fun restoreOnlyOnConfirm() =
        runTest {
            viewModel.selectFile(putBackup())
            viewModel.setPassword("abc123")
            viewModel.decryptPreview()

            // 解密只做预览
            assertTrue(db.txnDao().listAllIncludingDeleted().isEmpty())

            viewModel.confirmRestore()
            advanceUntilIdle()

            assertTrue(viewModel.state.value.done)
            assertEquals(2, db.txnDao().listAllIncludingDeleted().size)
            assertEquals("a1", db.accountDao().listAll().single().id)
        }

    @Test
    fun backToPassword() =
        runTest {
            viewModel.selectFile(putBackup())
            viewModel.setPassword("abc123")
            viewModel.decryptPreview()
            viewModel.backToPassword()
            assertNull(viewModel.state.value.preview)
            assertEquals("", viewModel.state.value.password)
            assertEquals("backup.xxjzbak", viewModel.state.value.fileName)
        }

    @Test
    fun confirmWithoutPreview() =
        runTest {
            viewModel.confirmRestore()
            advanceUntilIdle()
            assertFalse(viewModel.state.value.done)
            assertTrue(db.txnDao().listAllIncludingDeleted().isEmpty())
        }
}

/** M8 导出流程（AC-68、AC-69、AC-70）。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BackupViewModelTest {
    private val codec = BackupCodec()
    private val io = FakeDocumentIo()
    private val settingsFlow = MutableStateFlow(AppSettings())

    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsDataStore
    private lateinit var viewModel: BackupViewModel

    @Before
    fun setUp() {
        db = TestDatabaseFactory.create()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        settings = mockk(relaxed = true)
        coEvery { settings.settings } returns settingsFlow
        coEvery { settings.current() } returns AppSettings()
        coJustRun { settings.markBackedUp(any()) }
        viewModel =
            BackupViewModel(
                builder = com.xxjz.ledger.data.backup.BackupPayloadBuilder(db),
                codec = codec,
                settings = settings,
                io = io,
            )
        viewModel.ioDispatcher = UnconfinedTestDispatcher()
    }

    @After
    fun tearDown() {
        if (this::db.isInitialized) db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun shortPassword() =
        runTest {
            viewModel.setPassword("123")
            viewModel.setConfirmPassword("123")
            assertFalse(viewModel.validate())
            assertEquals("密码至少 6 位", viewModel.state.value.passwordError)
        }

    @Test
    fun mismatch() =
        runTest {
            viewModel.setPassword("123456")
            viewModel.setConfirmPassword("123457")
            assertFalse(viewModel.validate())
            assertEquals("两次输入的密码不一致", viewModel.state.value.passwordError)
        }

    @Test
    fun exportOk() =
        runTest {
            viewModel.setPassword("123456")
            viewModel.setConfirmPassword("123456")
            io.displayNameOverride = "我的备份.xxjzbak"

            viewModel.exportTo("content://test/out.xxjzbak")
            advanceUntilIdle()

            val s = viewModel.state.value

            assertNull(s.passwordError)
            val file = s.exported
            assertNotNull(file)
            assertEquals("我的备份.xxjzbak", file!!.name)
            assertTrue(file.sizeBytes > 0)
            assertTrue(file.sizeLabel.endsWith("B") || file.sizeLabel.endsWith("KB"))

            // 用同一密码真的能解回来，且版本号写的是当前 schema
            val decoded = codec.decode(io.files.getValue("content://test/out.xxjzbak"), "123456", AppDatabase.SCHEMA_VERSION)
            assertEquals(AppDatabase.SCHEMA_VERSION, decoded.schemaVersion)
        }

    @Test
    fun exportWriteFails() =
        runTest {
            io.failOnWrite = true
            viewModel.setPassword("123456")
            viewModel.setConfirmPassword("123456")
            viewModel.exportTo("content://test/out.xxjzbak")
            advanceUntilIdle()
            val s = viewModel.state.value
            assertNull(s.exported)
            assertTrue(s.passwordError!!.contains("导出失败"))
            assertFalse(s.exporting)
        }

    @Test
    fun markBackedUp() =
        runTest {
            viewModel.setPassword("123456")
            viewModel.setConfirmPassword("123456")
            viewModel.exportTo("content://test/out.xxjzbak")
            advanceUntilIdle()
            coVerify(exactly = 1) { settings.markBackedUp(any()) }
        }

    @Test
    fun clearAfterDone() =
        runTest {
            viewModel.setPassword("123456")
            viewModel.setConfirmPassword("123456")
            viewModel.exportTo("content://test/out.xxjzbak")
            advanceUntilIdle()
            viewModel.clearExported()
            assertEquals("", viewModel.state.value.password)
            assertNull(viewModel.state.value.exported)
        }
}
