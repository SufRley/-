package com.xxjz.ledger.core.designsystem.theme

import androidx.compose.ui.graphics.Color

/** 品牌主色：沉稳的墨绿，配合收支语义色使用。 */
val GreenPrimary = Color(0xFF2E7D5B)
val GreenPrimaryDark = Color(0xFF8ED4B0)
val GreenContainerLight = Color(0xFFD3F0DF)
val GreenContainerDark = Color(0xFF1D4735)

/** 收入 / 支出语义色（浅色主题）。 */
val IncomeGreenLight = Color(0xFF1F8A54)
val ExpenseRedLight = Color(0xFFD9453D)

/** 收入 / 支出语义色（深色主题）。 */
val IncomeGreenDark = Color(0xFF6FD79B)
val ExpenseRedDark = Color(0xFFFF8A80)

val NeutralSurfaceLight = Color(0xFFF7F8F7)
val NeutralSurfaceDark = Color(0xFF121513)
val WarningAmber = Color(0xFFE08A00)
val OverBudgetRed = Color(0xFFD9453D)

/**
 * 转账 / 借贷语义色（v1.9.3 第 6 条）。
 *
 * 转账是「左口袋到右口袋」，用蓝色表示中性搬运；借贷牵扯到别人，用紫色单独区分。
 * 两者都不该跟收支的红绿混用——那会让用户以为账目有盈亏。
 */
val TransferBlueLight = Color(0xFF3B7DD8)
val TransferBlueDark = Color(0xFF7FAEEE)
val DebtPurpleLight = Color(0xFF7A5CB8)
val DebtPurpleDark = Color(0xFFB49CE8)
