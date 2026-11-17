package com.xxjz.ledger.feature.editor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** v1.9.3 第 3 条：相机权限决策与采样式压缩。 */
@DisplayName("相机权限与照片压缩")
class CameraPermissionTest {
    @Test
    @DisplayName("已授权时直接开拍，不再弹框")
    fun alreadyGranted() {
        assertEquals(
            CameraRequest.LAUNCH,
            cameraRequestOf(hasPermission = true, everAsked = false, showsRationale = false),
        )
    }

    @Test
    @DisplayName("第一次点拍照要去申请")
    fun firstAsk() {
        assertEquals(
            CameraRequest.ASK,
            cameraRequestOf(hasPermission = false, everAsked = false, showsRationale = false),
        )
    }

    @Test
    @DisplayName("拒绝过一次但还能再问时继续申请")
    fun askAgain() {
        assertEquals(
            CameraRequest.ASK,
            cameraRequestOf(hasPermission = false, everAsked = true, showsRationale = true),
        )
    }

    @Test
    @DisplayName("勾了不再询问就直接引导设置，不再无效 launch")
    fun permanentlyDenied() {
        assertEquals(
            CameraRequest.BLOCKED,
            cameraRequestOf(hasPermission = false, everAsked = true, showsRationale = false),
        )
    }

    @Test
    @DisplayName("允许后走开拍")
    fun granted() {
        assertEquals(CameraResult.GRANTED, cameraResultOf(granted = true, showsRationale = false))
    }

    @Test
    @DisplayName("拒绝但还能再问：降级到相册")
    fun deniedOnce() {
        assertEquals(CameraResult.DENIED, cameraResultOf(granted = false, showsRationale = true))
    }

    @Test
    @DisplayName("拒绝且不再询问：引导去设置")
    fun deniedForever() {
        assertEquals(CameraResult.BLOCKED, cameraResultOf(granted = false, showsRationale = false))
    }

    @Test
    @DisplayName("采样率保证解码后长边不超过上限的两倍")
    fun sampleSize() {
        assertEquals(1, sampleSizeOf(1080, 1920))
        assertEquals(1, sampleSizeOf(1600, 1600))
        assertEquals(2, sampleSizeOf(3200, 3200))
        // 4000×3000：采样 2 后是 2000×1500，再缩到长边 1600，两段式留了余量
        assertEquals(2, sampleSizeOf(4000, 3000))
        assertEquals(4, sampleSizeOf(8000, 6000))
    }

    @Test
    @DisplayName("采样后长边仍不超过上限两倍，说明缩放那一步不会把图放大")
    fun sampleKeepsEdgeBounded() {
        listOf(1000 to 800, 4000 to 3000, 8000 to 6000, 12000 to 9000).forEach { (w, h) ->
            val sample = sampleSizeOf(w, h)
            val edge = maxOf(w / sample, h / sample)
            assertTrue(edge <= MAX_PHOTO_EDGE * 2, "长边 $edge 超过了两倍上限")
        }
    }
}
