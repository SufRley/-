package com.xxjz.ledger.feature.editor

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xxjz.ledger.core.common.MoneyFormatter
import com.xxjz.ledger.domain.model.Category
import com.xxjz.ledger.domain.model.DebtDirection
import com.xxjz.ledger.domain.model.TxnType
import kotlinx.coroutines.launch

/**
 * 全屏记账页（AC-01，v1.9.1 第 5 条）。
 *
 * 用户的原话是「不要抽屉，点加号后是另一个页面」，所以这里不再是 ModalBottomSheet，
 * 而是一个正经的二级页。自上而下严格按反馈里的顺序排：
 *
 * 1. 四个类型 Tab（支出 / 收入 / 转账 / 借贷）
 * 2. 分类选择器 + 箭头 ｜ 右侧金额
 * 3. 三行四列快捷分类（只在收支下出现）
 * 4. 付款账户行（右侧当前账户）+ 单选 / 组合；组合时展开分摊输入与可 × 的标签
 * 5. 备注输入框 + 相机按钮（拍照 / 相册）
 * 6. 两行横向滑动的常用标签
 * 7. 计算器键盘 + 保存
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
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picks = remember { PickHost() }
    var newDebtName by remember { mutableStateOf("") }
    var showPhotoMenu by remember { mutableStateOf(false) }
    var showPermissionGuide by remember { mutableStateOf(false) }
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
            if (event is EditorEvent.Saved) onSaved()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .testTag("editor_screen"),
        ) {
            EditorHeader(
                editing = state.editingId != null,
                saving = state.saving,
                onBack = onDismiss,
                onSave = viewModel::submitOrEvaluate,
            )
            EditorTypeTabs(current = state.type, onSelect = viewModel::setType)

            // 内容区吃满剩余空间，计算器永远贴底，键盘弹起也不会把输入区挤没
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
                    Spacer(Modifier.height(12.dp))
                    QuickCategoryGrid(
                        items = state.quickCategories,
                        selectedId = state.categoryId,
                        onSelect = viewModel::selectCategory,
                    )
                }
                Spacer(Modifier.height(14.dp))
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
                Spacer(Modifier.height(12.dp))
                NoteAndPhotoRow(
                    note = state.note,
                    onNoteChange = viewModel::setNote,
                    hasPhoto = state.photoPath != null,
                    processing = photos.processing.value,
                    onPhotoClick = { showPhotoMenu = true },
                    onPhotoRemove = { viewModel.setPhoto(null) },
                )
                Spacer(Modifier.height(10.dp))
                TagStrip(
                    tags = state.tags,
                    selected = state.selectedTagIds,
                    onToggle = viewModel::toggleTag,
                )
                state.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
            }

            CalcPad(
                expression = state.calc.expressionText,
                display = if (state.splitMode) MoneyFormatter.centsToYuan(state.splitTotal) else state.amountDisplay,
                error = state.calc.error,
                accentIncome = state.type == TxnType.INCOME,
                // 组合支付时金额由各账户分摊决定，计算器置灰避免两套真相
                enabled = !state.splitMode,
                onKey = { key -> handleKey(viewModel, key) },
                onBackspace = viewModel::backspace,
                onBackspaceLong = { repeat(8) { viewModel.backspace() } },
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
        close = { which ->
            when (which) {
                Pick.CATEGORY -> picks.category = false
                Pick.ACCOUNT -> picks.account = false
                Pick.TO_ACCOUNT -> picks.toAccount = false
                Pick.DEBT_ACCOUNT -> picks.debtAccount = false
                Pick.NEW_DEBT -> picks.newDebt = false
                Pick.DATE -> picks.date = false
            }
        },
    )

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
        AlertDialog(
            onDismissRequest = { showPermissionGuide = false },
            title = { Text("相机权限已被关闭") },
            text = { Text("你之前拒绝过相机权限，系统不会再弹询问框。可以去设置里重新打开，也可以直接从相册选照片。") },
            confirmButton = {
                TextButton(
                    onClick = {
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
                    modifier = Modifier.testTag("camera_open_settings"),
                ) { Text("去设置") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPermissionGuide = false
                        photos.pickFromGallery()
                    },
                    modifier = Modifier.testTag("camera_use_gallery"),
                ) { Text("用相册") }
            },
        )
    }
}

/** 六个选择器弹窗的显隐开关，用一个可变容器装着，免得在函数体里摊六个 remember。 */
private class PickHost {
    var category by mutableStateOf(false)
    var account by mutableStateOf(false)
    var toAccount by mutableStateOf(false)
    var debtAccount by mutableStateOf(false)
    var newDebt by mutableStateOf(false)
    var date by mutableStateOf(false)
}

/**
 * 顶部：返回 ＋ 标题 ＋ 保存。
 *
 * 不用 TopAppBar：64dp + 状态栏 inset 会把下面的内容整体压低，
 * 而这一页的信息密度很高，一格都不能浪费（v1.9.1 顺手在二级页统一成 CompactHeader 风格）。
 */
@Composable
private fun EditorHeader(
    editing: Boolean,
    saving: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("editor_back")) {
            Icon(Icons.Filled.Close, contentDescription = "关闭")
        }
        Text(
            text = if (editing) "编辑账目" else "记一笔",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = onSave,
            enabled = !saving,
            shape = RoundedCornerShape(50),
            contentPadding = PaddingValues(horizontal = 20.dp),
            modifier = Modifier.height(38.dp).testTag("btn_save"),
        ) {
            Text(if (editing) "保存" else "记账", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 四个类型 Tab：支出 / 收入 / 转账 / 借贷。 */
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
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tabs.forEach { (type, label) ->
            val selected = type == current
            Surface(
                onClick = { onSelect(type) },
                shape = RoundedCornerShape(50),
                color =
                    if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                modifier = Modifier.weight(1f).testTag("type_${type.name.lowercase()}"),
            ) {
                Text(
                    text = label,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color =
                        if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
                )
            }
        }
    }
}

/**
 * 第二行：左边「分类 ＋ >」，右边金额。
 *
 * 金额用 headlineMedium 而不是原来的 40sp 超大号：这一页下面还有 12 个格子、账户行、
 * 备注、标签、计算器，金额再占两行高就挤了。字号降下来但加粗，重点仍然在。
 */
@Composable
private fun CategoryAmountRow(
    state: EditorUiState,
    onPickCategory: () -> Unit,
) {
    val label =
        when (state.type) {
            TxnType.TRANSFER -> "转账"
            TxnType.DEBT -> if (state.debtDirection == DebtDirection.LEND) "我借出" else "我借入"
            else -> state.categoryName ?: "选分类"
        }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onPickCategory,
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant,
            enabled = state.type == TxnType.EXPENSE || state.type == TxnType.INCOME,
            modifier = Modifier.testTag("chip_category"),
        ) {
            Row(
                modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "选择分类",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = "¥ ${state.amountDisplay}",
            style = MaterialTheme.typography.headlineMedium,
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
            color =
                when {
                    state.calc.error != null -> MaterialTheme.colorScheme.error
                    state.type == TxnType.INCOME -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            modifier = Modifier.testTag("amount_display"),
        )
    }
}

/** 三行四列快捷分类：常用优先，选中打勾。 */
@Composable
private fun QuickCategoryGrid(
    items: List<Category>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("quick_categories")) {
        items.chunked(4).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                rowItems.forEach { category ->
                    val selected = category.id == selectedId
                    Surface(
                        onClick = { onSelect(category.id) },
                        shape = RoundedCornerShape(12.dp),
                        color =
                            if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            },
                        modifier = Modifier.weight(1f).height(44.dp).testTag("quick_${category.id}"),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Text(
                                text = category.name,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                color =
                                    if (selected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                            if (selected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp).align(Alignment.TopEnd).padding(top = 3.dp, end = 3.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
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

/** 备注 + 照片的占位小圆点，尺寸与会话按钮一致。 */
private val PhotoButtonSize = 44.dp

/** 备注输入框与相机按钮。 */
@Composable
private fun NoteAndPhotoRow(
    note: String,
    onNoteChange: (String) -> Unit,
    hasPhoto: Boolean,
    processing: Boolean,
    onPhotoClick: () -> Unit,
    onPhotoRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = note,
            onValueChange = onNoteChange,
            placeholder = { Text("添加备注…") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f).testTag("note_input"),
        )
        Box {
            Surface(
                // 压缩落盘期间不接受点击：重复点会再拉一次相机，回来两个结果互相覆盖
                onClick = { if (!processing) onPhotoClick() },
                shape = RoundedCornerShape(12.dp),
                color =
                    if (hasPhoto) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                modifier = Modifier.size(PhotoButtonSize).testTag("btn_photo"),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    if (processing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp).testTag("photo_processing"),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.PhotoCamera,
                            contentDescription = if (hasPhoto) "已附收据，点击更换" else "添加收据照片",
                            modifier = Modifier.size(22.dp),
                            tint =
                                if (hasPhoto) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }
                }
            }
            if (hasPhoto) {
                Surface(
                    onClick = onPhotoRemove,
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp).align(Alignment.TopEnd).testTag("btn_photo_remove"),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "移除照片",
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onError,
                        )
                    }
                }
            }
        }
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

/** 转账手续费输入。 */
@Composable
internal fun FeeField(
    value: String,
    onChange: (String) -> Unit,
) {
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("手续费") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("fee_input"),
    )
}
