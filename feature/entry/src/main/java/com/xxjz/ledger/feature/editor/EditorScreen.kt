package com.xxjz.ledger.feature.editor

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.core.designsystem.component.CategoryAvatar
import com.xxjz.ledger.core.designsystem.theme.LocalAmountColors
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.DebtDirection
import com.xxjz.ledger.domain.model.TxnType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全屏记账页（v1.9.1 第 5 条建的页，v1.9.3 第 6、7 条按参考图重排）。
 *
 * 自上而下严格按参考图的顺序：
 *
 * 1. 页头：返回 · 标题 · ♡ 模板 · ⚙ 设置
 * 2. 四个类型 Tab（纯文字 + 下划线，不再是填色胶囊）
 * 3. 分类行：圆形头像 + 分类名 + › ｜ 右侧按类型着色的大号金额
 * 4. 三行四列快捷分类（只在收支下出现）
 * 5. 付款账户行（图标 + 账户名 + 行尾一个组合切换图标）
 * 6. 备注（无边框）+ 相机（有照片时显示缩略图）
 * 7. 两行状态胶囊：日期 / 时间 / 标签 / 商家 // 报销 / 退款 / 分期 / 计入
 * 8. 键盘：3 列数字 + 1 列功能（⌫ / 再记 / 跨两行的保存）
 *
 * 新建与编辑共用这一个页面（用户拍板），靠 [editTxnId] 区分。
 */
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    editTxnId: String? = null,
    templateId: String? = null,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenTemplates: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picks = remember { PickHost() }
    var newDebtName by remember { mutableStateOf("") }
    var showPhotoMenu by remember { mutableStateOf(false) }
    var showPermissionGuide by remember { mutableStateOf(false) }
    var showTemplates by remember { mutableStateOf(false) }
    var calculatorMode by remember { mutableStateOf(false) }
    val photos = rememberPhotoCapture(onCaptured = viewModel::setPhoto)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // 拍照前的相机权限闸门（v1.9.3 第 3 条）：不申请就会在 startActivity 时抛 SecurityException
    val cameraGate =
        rememberCameraGate(
            onGranted = {
                if (!photos.takePhoto()) {
                    scope.launch { snackbar.showSnackbar("这台设备没有可用的相机，已为你打开相册") }
                    photos.pickFromGallery()
                }
            },
            onDenied = {
                scope.launch { snackbar.showSnackbar("未授予相机权限，已为你打开相册") }
                photos.pickFromGallery()
            },
            onBlocked = { showPermissionGuide = true },
        )

    LaunchedEffect(editTxnId) {
        if (editTxnId != null) viewModel.loadForEdit(editTxnId)
    }
    LaunchedEffect(templateId) {
        if (templateId != null) viewModel.loadTemplate(templateId)
    }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                // 「再记」不关页：连续记同类账时，关掉再点加号是多余的两次操作
                is EditorEvent.SavedAndNext ->
                    snackbar.showSnackbar("已记一笔 ${MoneyFormatter.centsToYuan(event.amountCents, showSymbol = true)}")
                is EditorEvent.Saved -> onSaved()
                is EditorEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().testTag("editor_screen")) {
            EditorHeader(
                editing = state.editingId != null,
                onBack = onDismiss,
                onTemplates = { showTemplates = true },
                onSettings = onOpenSettings,
            )
            EditorTypeTabs(current = state.type, onSelect = viewModel::setType)

            // 内容区吃满剩余空间：键盘永远贴底，内容再多也只在自己的区域里滚
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
            ) {
                CategoryAmountRow(state = state, onPickCategory = { picks.category = true })
                if (state.type == TxnType.EXPENSE || state.type == TxnType.INCOME) {
                    Spacer(Modifier.height(10.dp))
                    QuickCategoryGrid(
                        items = state.quickCategories,
                        selectedId = state.categoryId,
                        onSelect = viewModel::selectCategory,
                    )
                }
                Spacer(Modifier.height(10.dp))
                PaymentSection(
                    state = state,
                    viewModel = viewModel,
                    onPickAccount = { picks.account = true },
                    onPickToAccount = { picks.toAccount = true },
                    onPickDebtAccount = { picks.debtAccount = true },
                    onNewDebt = {
                        newDebtName = ""
                        picks.newDebt = true
                    },
                )
                EditorHairline(modifier = Modifier.padding(top = 4.dp))
                NoteAndPhotoRow(
                    note = state.note,
                    onNoteChange = viewModel::setNote,
                    photoPath = state.photoPath,
                    processing = photos.processing.value,
                    onPhotoClick = { showPhotoMenu = true },
                    onPhotoRemove = { viewModel.setPhoto(null) },
                )
                EditorHairline()
                Spacer(Modifier.height(12.dp))
                EditorStateChips(state = state, viewModel = viewModel)
                state.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(14.dp))
            }

            EditorKeypad(
                display =
                    KeypadDisplay(
                        expression = state.calc.expressionText,
                        display = if (state.splitMode) MoneyFormatter.centsToYuan(state.splitTotal) else state.amountDisplay,
                        error = state.calc.error,
                        accent = amountColorOf(state.type),
                        inputEnabled = !state.splitMode,
                    ),
                actions =
                    KeypadActions(
                        onKey = { key -> handleKey(viewModel, key) },
                        onBackspace = viewModel::backspace,
                        onBackspaceLong = { repeat(8) { viewModel.backspace() } },
                        onSave = { viewModel.submitOrEvaluate() },
                        onSaveAndNext = { viewModel.submitOrEvaluate(stayOpen = true) },
                        onToggleCalculator = { calculatorMode = !calculatorMode },
                        onBlocked = { reason -> scope.launch { snackbar.showSnackbar(reason) } },
                    ),
                calculatorMode = calculatorMode,
                saveBlocker = state.saveBlocker,
                // 编辑模式与借贷下不提供「再记」：前者会记成两笔同一账，后者本就不是高频连续动作
                showSaveAndNext = state.editingId == null && state.type != TxnType.DEBT,
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    EditorPickDialogs(
        viewModel = viewModel,
        state = state,
        show =
            PickVisibility(
                category = picks.category,
                account = picks.account,
                toAccount = picks.toAccount,
                debtAccount = picks.debtAccount,
                newDebt = picks.newDebt,
                date = picks.date,
            ),
        newDebtName = newDebtName,
        onNewDebtNameChange = { newDebtName = it },
        close = picks::close,
    )

    if (showTemplates) {
        EditorTemplateSheet(
            templates = state.templates,
            categoryOf = { id ->
                state.categories.firstOrNull { it.id == id }?.let { it.name to it.color }
            },
            onPick = { id ->
                showTemplates = false
                scope.launch { viewModel.loadTemplate(id) }
            },
            onManage = {
                showTemplates = false
                onOpenTemplates()
            },
            onDismiss = { showTemplates = false },
        )
    }

    if (showPhotoMenu) {
        PhotoSourceDialog(
            onCamera = {
                showPhotoMenu = false
                cameraGate.request()
            },
            onGallery = {
                showPhotoMenu = false
                photos.pickFromGallery()
            },
            onDismiss = { showPhotoMenu = false },
        )
    }

    if (showPermissionGuide) {
        CameraPermissionGuide(
            onOpenSettings = {
                showPermissionGuide = false
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ),
                    )
                }
            },
            onUseGallery = {
                showPermissionGuide = false
                photos.pickFromGallery()
            },
            onDismiss = { showPermissionGuide = false },
        )
    }
}

/**
 * 「相机权限被永久拒绝」的引导。
 *
 * 抽成独立函数纯粹是为了给 [EditorScreen] 减行数——它已经顶到 detekt 的 `LongMethod` 上限，
 * 而这三条路径（去设置 / 用相册 / 关掉）本身又是一个完整的小决策，单独拿出来反而更好读。
 */
@Composable
private fun CameraPermissionGuide(
    onOpenSettings: () -> Unit,
    onUseGallery: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("相机权限已被关闭") },
        text = { Text("你之前拒绝过相机权限，系统不会再弹询问框。可以去设置里重新打开，也可以直接从相册选照片。") },
        confirmButton = {
            TextButton(onClick = onOpenSettings, modifier = Modifier.testTag("camera_open_settings")) { Text("去设置") }
        },
        dismissButton = {
            TextButton(onClick = onUseGallery, modifier = Modifier.testTag("camera_use_gallery")) { Text("用相册") }
        },
    )
}

/** 六个选择器弹窗的显隐开关，用一个可变容器装着，免得在函数体里摊六个 remember。 */
private class PickHost {
    var category by mutableStateOf(false)
    var account by mutableStateOf(false)
    var toAccount by mutableStateOf(false)
    var debtAccount by mutableStateOf(false)
    var newDebt by mutableStateOf(false)
    var date by mutableStateOf(false)

    /** 关掉指定的那个弹窗。收进类里，页面主体就只剩一行 `close = picks::close`。 */
    fun close(which: Pick) {
        when (which) {
            Pick.CATEGORY -> category = false
            Pick.ACCOUNT -> account = false
            Pick.TO_ACCOUNT -> toAccount = false
            Pick.DEBT_ACCOUNT -> debtAccount = false
            Pick.NEW_DEBT -> newDebt = false
            Pick.DATE -> date = false
        }
    }
}

/** 当前类型的金额主色（支出红 / 收入绿 / 转账蓝 / 借贷紫）。 */
@Composable
internal fun amountColorOf(type: TxnType): Color {
    val colors = LocalAmountColors.current
    return when (type) {
        TxnType.EXPENSE -> colors.expense
        TxnType.INCOME -> colors.income
        TxnType.TRANSFER -> colors.transfer
        TxnType.DEBT -> colors.debt
    }
}

/**
 * 页头：返回 ＋ 标题 ＋ 爱心（模板）＋ 齿轮（设置）。
 *
 * 保存按钮从这里搬到键盘右下角——参考图的做法，也是拇指更容易够到的位置。
 * 不用 TopAppBar：64dp + 状态栏 inset 会把内容整体压低，而这一页一格都不能浪费。
 */
@Composable
private fun EditorHeader(
    editing: Boolean,
    onBack: () -> Unit,
    onTemplates: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 6.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("editor_back")) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
        }
        Text(
            text = if (editing) "编辑账目" else "记一笔",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onTemplates, modifier = Modifier.testTag("editor_templates")) {
            Icon(Icons.Outlined.FavoriteBorder, contentDescription = "记账模板")
        }
        IconButton(onClick = onSettings, modifier = Modifier.testTag("editor_settings")) {
            Icon(Icons.Outlined.Settings, contentDescription = "设置")
        }
    }
}

/**
 * 四个类型 Tab：纯文字 + 2dp 下划线（PRD 6.3）。
 *
 * 换掉填色胶囊是因为参考图里 Tab 更像「分段标题」而不是「一排按钮」：
 * 四个胶囊同时存在时，未选中的三个也在抢注意力，整页视觉噪声很大。
 */
@Composable
private fun EditorTypeTabs(
    current: TxnType,
    onSelect: (TxnType) -> Unit,
) {
    val tabs =
        listOf(
            TxnType.EXPENSE to "支出",
            TxnType.INCOME to "收入",
            TxnType.TRANSFER to "转账",
            TxnType.DEBT to "借贷",
        )
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            tabs.forEach { (type, label) ->
                val selected = type == current
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clickable { onSelect(type) }
                            .testTag("type_${type.name.lowercase()}"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = label,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color =
                            if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .height(2.dp)
                            .width(24.dp)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                RoundedCornerShape(2.dp),
                            ),
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

/**
 * 分类 + 金额行（PRD 6.4）。
 *
 * 左边是「圆形头像 → 分类名 → ›」，整块左半区都可点；右边是按类型着色的大号金额。
 * 金额从 30sp 收到 28sp Medium：这一页下面还有 12 个分类格、账户行、备注、胶囊和键盘，
 * 再大的字号会把内容挤到滚动区里，用户得划一下才能看到付款账户。
 */
@Composable
private fun CategoryAmountRow(
    state: EditorUiState,
    onPickCategory: () -> Unit,
) {
    val moneyType = state.type == TxnType.EXPENSE || state.type == TxnType.INCOME
    val label =
        when (state.type) {
            TxnType.TRANSFER -> "转账"
            TxnType.DEBT -> if (state.debtDirection == DebtDirection.LEND) "我借出" else "我借入"
            else -> state.categoryName
        }
    val selected = state.categories.firstOrNull { it.id == state.categoryId }
    val empty = state.amountCents == null

    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clickable(enabled = moneyType, onClick = onPickCategory)
                    .testTag("chip_category"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (moneyType) {
                CategoryAvatar(name = selected?.name, rawColor = selected?.color.orEmpty())
            } else {
                Icon(
                    imageVector = if (state.type == TxnType.TRANSFER) Icons.Filled.SwapHoriz else Icons.Filled.Handshake,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp).padding(6.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(8.dp))
            Text(
                text = label ?: "选择分类",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (label == null) FontWeight.Normal else FontWeight.Medium,
                color =
                    if (label == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                maxLines = 1,
            )
            if (moneyType) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "选择分类",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = "¥ ${state.amountDisplay}",
            style = MaterialTheme.typography.headlineSmall,
            fontSize = 28.sp,
            fontWeight = FontWeight.Medium,
            color =
                when {
                    state.calc.error != null -> MaterialTheme.colorScheme.error
                    // 还没输入时降到 40% 透明度：0.00 是占位，不该和真金额一样抢眼
                    empty -> amountColorOf(state.type).copy(alpha = 0.4f)
                    else -> amountColorOf(state.type)
                },
            modifier = Modifier.testTag("amount_display"),
        )
    }
}

/** 三行四列快捷分类：浅灰底，选中填主色 + 白字，右上角一个勾。 */
@Composable
private fun QuickCategoryGrid(
    items: List<Category>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("quick_categories")) {
        items.chunked(4).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                rowItems.forEach { category ->
                    val selected = category.id == selectedId
                    Surface(
                        onClick = { onSelect(category.id) },
                        shape = RoundedCornerShape(50),
                        color =
                            if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            },
                        modifier = Modifier.weight(1f).height(36.dp).testTag("quick_${category.id}"),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Text(
                                text = category.name,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                color =
                                    if (selected) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                            if (selected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp).align(Alignment.TopEnd).padding(top = 3.dp, end = 3.dp),
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                    }
                }
                // 最后一行不足 4 个时补空位，保持格子宽度一致
                repeat(4 - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 备注行高度，与相机按钮同高，视觉上是一条整齐的横线。 */
private val NoteRowHeight = 52.dp

/**
 * 备注 + 相机（PRD 6.7）。
 *
 * 备注改 `BasicTextField` + `decorationBox`：`OutlinedTextField` 自带描边和浮动标签，
 * 在这一页里等于给一行文字套了个方框，和整体「无卡片、靠留白分层」的调子不搭。
 */
@Composable
private fun NoteAndPhotoRow(
    note: String,
    onNoteChange: (String) -> Unit,
    photoPath: String?,
    processing: Boolean,
    onPhotoClick: () -> Unit,
    onPhotoRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(NoteRowHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicTextField(
            value = note,
            onValueChange = onNoteChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.weight(1f).testTag("note_input"),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (note.isEmpty()) {
                        Text(
                            text = "备注...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                    inner()
                }
            },
        )
        Box {
            Surface(
                // 压缩落盘期间不接受点击：重复点会再拉一次相机，回来两个结果互相覆盖
                onClick = { if (!processing) onPhotoClick() },
                shape = RoundedCornerShape(8.dp),
                color =
                    if (photoPath != null) {
                        Color.Transparent
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    },
                modifier = Modifier.size(36.dp).testTag("btn_photo"),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    when {
                        processing ->
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp).testTag("photo_processing"),
                                strokeWidth = 2.dp,
                            )
                        // 有照片就直接显示缩略图，「这张是不是我要的」一眼能看出来
                        photoPath != null -> PhotoThumbnail(path = photoPath)
                        else ->
                            Icon(
                                imageVector = Icons.Outlined.PhotoCamera,
                                contentDescription = "添加收据照片",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                    }
                }
            }
            if (photoPath != null) {
                Surface(
                    onClick = onPhotoRemove,
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp).align(Alignment.TopEnd).testTag("btn_photo_remove"),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "移除照片",
                            modifier = Modifier.size(11.dp),
                            tint = MaterialTheme.colorScheme.onError,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 收据缩略图。
 *
 * 不引 Coil：为了 36dp 的一个方块装一整个图片库不划算。照片在
 * [rememberPhotoCapture] 里已经压到长边 1280 以内，这里再按 `inSampleSize = 8`
 * 解码，一张图只占几十 KB 内存，滑走就回收。
 */
@Composable
private fun PhotoThumbnail(path: String) {
    val bitmap by
        produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, path) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val options = BitmapFactory.Options().apply { inSampleSize = 8 }
                        BitmapFactory.decodeFile(path, options)?.asImageBitmap()
                    }.getOrNull()
                }
        }
    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = "已附收据",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Icon(
            imageVector = Icons.Outlined.PhotoCamera,
            contentDescription = "已附收据",
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun handleKey(
    viewModel: EditorViewModel,
    key: String,
) {
    when (key) {
        "+" -> viewModel.operator(CalcOp.ADD)
        "-" -> viewModel.operator(CalcOp.SUB)
        "×" -> viewModel.operator(CalcOp.MUL)
        "÷" -> viewModel.operator(CalcOp.DIV)
        "." -> viewModel.dot()
        else -> key.firstOrNull()?.takeIf { it.isDigit() }?.let { viewModel.digit(it) }
    }
}
