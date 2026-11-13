package com.xxjz.ledger.core.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** M9-C 应用锁（AC-86）：状态机与 PIN 凭据。 */
@DisplayName("应用锁")
class AppLockTest {
    @Nested
    @DisplayName("状态机")
    inner class Guard {
        private var clock = 1_000_000L
        private val guard = AppLockGuard(now = { clock })

        private fun rightPin(pin: String) = pin == "1234"

        @Test
        @DisplayName("没开锁时永远不拦人")
        fun disabled() {
            guard.configure(enabled = false, hasPin = true)
            assertFalse(guard.state.locked)
            guard.onBackground()
            clock += 10 * 60_000
            guard.onForeground()
            assertFalse(guard.state.locked)
            assertTrue(guard.unlockWithPin("0000") { false })
        }

        @Test
        @DisplayName("开启后立即上锁，解锁后放行")
        fun lockOnEnable() {
            guard.configure(enabled = true, hasPin = true)
            assertTrue(guard.state.locked)
            assertTrue(guard.unlockWithPin("1234", ::rightPin))
            assertFalse(guard.state.locked)
        }

        @Test
        @DisplayName("切后台 1 分钟内回来不重新锁，超过才锁")
        fun grace() {
            guard.configure(enabled = true, hasPin = true)
            guard.unlockWithPin("1234", ::rightPin)

            guard.onBackground()
            clock += 30_000
            guard.onForeground()
            assertFalse(guard.state.locked)

            guard.onBackground()
            clock += 61_000
            guard.onForeground()
            assertTrue(guard.state.locked)
        }

        @Test
        @DisplayName("生物识别通过直接解锁，取消则保持锁定且不计失败")
        fun biometric() {
            guard.configure(enabled = true, hasPin = true)
            guard.onBiometricCancelled()
            assertTrue(guard.state.locked)
            assertEquals(0, guard.state.failedAttempts)

            guard.onBiometricSuccess()
            assertFalse(guard.state.locked)
        }

        @Test
        @DisplayName("输错给剩余次数提示，第 5 次后锁死 PIN 通道")
        fun failedAttempts() {
            guard.configure(enabled = true, hasPin = true)
            repeat(4) { i ->
                assertFalse(guard.unlockWithPin("0000", ::rightPin))
                assertEquals("密码错误，还可尝试 ${4 - i} 次", guard.state.message)
            }
            assertFalse(guard.unlockWithPin("0000", ::rightPin))
            assertTrue(guard.state.blocked)
            assertTrue(guard.state.message!!.contains("指纹"))

            // 锁死后再输对也不放行，但生物识别仍然可以进
            assertFalse(guard.unlockWithPin("1234", ::rightPin))
            assertTrue(guard.state.locked)
            guard.onBiometricSuccess()
            assertFalse(guard.state.locked)
            assertEquals(0, guard.state.failedAttempts)
        }

        @Test
        @DisplayName("没设 PIN 时不能用密码解锁，提示明确")
        fun noPin() {
            guard.configure(enabled = true, hasPin = false)
            assertFalse(guard.unlockWithPin("1234", ::rightPin))
            assertEquals("还没有设置密码", guard.state.message)
        }

        @Test
        @DisplayName("重新解锁成功后失败计数归零，下次从 5 次重新算")
        fun resetAttempts() {
            guard.configure(enabled = true, hasPin = true)
            repeat(3) { guard.unlockWithPin("0000", ::rightPin) }
            assertEquals(3, guard.state.failedAttempts)
            guard.onBiometricSuccess()
            guard.configure(enabled = true, hasPin = true)
            assertEquals(0, guard.state.failedAttempts)
            // 重新配置不会把已解锁的人再锁一次
            assertFalse(guard.state.locked)
        }

        @Test
        @DisplayName("关掉锁会把计数与提示一起清干净")
        fun disableClears() {
            guard.configure(enabled = true, hasPin = true)
            repeat(2) { guard.unlockWithPin("0000", ::rightPin) }
            guard.configure(enabled = false, hasPin = true)
            assertEquals(0, guard.state.failedAttempts)
            assertFalse(guard.state.locked)
            guard.consumeMessage()
            assertNull(guard.state.message)
        }

        @Test
        @DisplayName("关掉锁之后 lockEnabled 记录偏好为 false，不再重排前台/后台事件")
        fun lockEnabledFlag() {
            guard.configure(enabled = true, hasPin = true)
            assertTrue(guard.lockEnabled)
            guard.configure(enabled = false, hasPin = true)
            assertFalse(guard.lockEnabled)
            guard.onBackground()
            clock += 10 * 60_000
            guard.onForeground()
            assertFalse(guard.state.locked)
        }
    }

    @Nested
    @DisplayName("PIN 凭据")
    inner class Pin {
        @Test
        @DisplayName("同一 PIN 同一盐结果稳定，不同盐结果不同")
        fun deterministic() {
            val salt = PinHasher.newSalt()
            val h1 = PinHasher.hash("1234", salt)
            val h2 = PinHasher.hash("1234", salt)
            assertEquals(h1, h2)
            assertEquals(64, h1.length) // SHA-256 → 32 字节 → 64 hex
            assertTrue(h1 != PinHasher.hash("1234", PinHasher.newSalt()))
        }

        @Test
        @DisplayName("校验只认对的 PIN")
        fun verify() {
            val salt = PinHasher.newSalt()
            val hash = PinHasher.hash("246810", salt)
            assertTrue(PinHasher.verify("246810", salt, hash))
            assertFalse(PinHasher.verify("246811", salt, hash))
        }

        @Test
        @DisplayName("缺盐或缺 hash 时校验一律失败，不会异常通过")
        fun emptyCredential() {
            assertFalse(PinHasher.verify("1234", "", "abc"))
            assertFalse(PinHasher.verify("1234", "00ff", ""))
        }

        @Test
        @DisplayName("必须是 4~8 位纯数字")
        fun format() {
            assertTrue(PinHasher.isValidFormat("1234"))
            assertTrue(PinHasher.isValidFormat("12345678"))
            assertFalse(PinHasher.isValidFormat("123"))
            assertFalse(PinHasher.isValidFormat("123456789"))
            assertFalse(PinHasher.isValidFormat("12a4"))
            assertFalse(PinHasher.isValidFormat(""))
        }

        @Test
        @DisplayName("盐是随机的，不会两次相同")
        fun saltRandom() {
            val a = PinHasher.newSalt()
            val b = PinHasher.newSalt()
            assertEquals(32, a.length)
            assertNotNull(a)
            assertFalse(a == b)
        }
    }
}
