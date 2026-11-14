package com.xxjz.ledger.data.local

import com.xxjz.ledger.data.local.dao.MetaDao
import com.xxjz.ledger.data.local.dao.MetaEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 内存版 [MetaDao]，供不需要真库的 ViewModel 测试使用。
 *
 * 放在 `data.local` 包下是有意的：`MetaDao` 的实现类天然属于这一层，
 * 测试替身跟着接口走比塞进 testutil 更好找。
 */
class MetaDaoFakeForReport : MetaDao {
    private val rows = MutableStateFlow<Map<String, String>>(emptyMap())

    override suspend fun get(key: String): String? = rows.value[key]

    override suspend fun put(meta: MetaEntity) {
        rows.value = rows.value + (meta.key to meta.value)
    }

    override suspend fun listByPrefix(prefix: String): List<MetaEntity> =
        rows.value.entries
            .filter { it.key.startsWith(prefix) }
            .sortedByDescending { it.key }
            .map { MetaEntity(it.key, it.value) }

    override fun observeByPrefix(prefix: String): Flow<List<MetaEntity>> =
        rows.map { map ->
            map.entries
                .filter { it.key.startsWith(prefix) }
                .sortedByDescending { it.key }
                .map { MetaEntity(it.key, it.value) }
        }
}
