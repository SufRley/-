package com.xxjz.ledger.di

import com.xxjz.ledger.domain.repository.CategoryRepository
import com.xxjz.ledger.domain.repository.MerchantRepository
import com.xxjz.ledger.domain.repository.TagRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 桌面小组件的取数入口（AC-83）。
 *
 * 小组件由系统实例化，拿不到 Activity 的依赖，只能从 Application 的 Hilt 容器里取。
 * 放在 di 包而不是 widget 包，是为了让「取依赖的入口」和「业务实现」分开，
 * 组件的 Composable 侧不需要知道 Hilt 的存在。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun widgetSummary(): com.xxjz.ledger.domain.usecase.GetWidgetSummaryUseCase
}

/** 分类 / 标签 / 商家管理页的入口（AC-79），同样供非 Activity 场景使用。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ManageEntryPoint {
    fun categoryRepository(): CategoryRepository

    fun tagRepository(): TagRepository

    fun merchantRepository(): MerchantRepository
}
