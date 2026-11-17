// 多模块统一约定：所有 library 模块共享同样的 Kotlin/Android/Compose/Hilt/测试配置，
// 各模块只声明「依赖谁」。依赖方向由 settings.gradle.kts + 各模块 dependencies 块强制。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.junit5) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}
