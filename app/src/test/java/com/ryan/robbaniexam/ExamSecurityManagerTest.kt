package com.ryan.robbaniexam

import com.ryan.robbaniexam.security.ExamSecurityManager
import com.ryan.robbaniexam.security.ViolationListener
import com.ryan.robbaniexam.security.ViolationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExamSecurityManagerTest {

    @Test
    fun recordViolation_deduplicatesWithinCooldownAndLocksAtMax() {
        var fakeNow = 10_000L
        var warningCallbacks = 0
        var lockedCallbacks = 0

        val manager = ExamSecurityManager(
            activity = null,
            maxViolations = 3,
            listener = object : ViolationListener {
                override fun onViolationDetected(type: ViolationType, message: String, currentViolations: Int, maxViolations: Int) {
                    warningCallbacks++
                }

                override fun onMaxViolationsExceeded(type: ViolationType, message: String) {
                    lockedCallbacks++
                }
            },
            violationCooldownMs = 1500L,
            timeProvider = { fakeNow }
        )

        // Two rapid lifecycle events within 300ms must count as 1 violation
        assertTrue(manager.recordViolation(ViolationType.APP_SWITCHED))
        fakeNow += 300L
        assertFalse(manager.recordViolation(ViolationType.APP_SWITCHED))
        assertEquals(1, manager.violationCount)
        assertEquals(1, warningCallbacks)
        assertFalse(manager.isExamLocked)

        // Second violation after cooldown
        fakeNow += 2000L
        assertTrue(manager.recordViolation(ViolationType.SPLIT_SCREEN_DETECTED))
        assertEquals(2, manager.violationCount)
        assertEquals(2, warningCallbacks)

        // Third violation triggers lock
        fakeNow += 2000L
        assertTrue(manager.recordViolation(ViolationType.APP_SWITCHED))
        assertEquals(3, manager.violationCount)
        assertTrue(manager.isExamLocked)
        assertEquals(1, lockedCallbacks)
        assertEquals(3, manager.violationHistory.size)

        // Unlock resets count and lock
        manager.unlockExam()
        assertFalse(manager.isExamLocked)
        assertEquals(0, manager.violationCount)
    }

    @Test
    fun recordViolation_suppressedWhileAdminDialogOpen() {
        val manager = ExamSecurityManager(activity = null, maxViolations = 3)
        manager.isAdminDialogOpen = true
        assertFalse(manager.recordViolation(ViolationType.APP_SWITCHED))
        assertEquals(0, manager.violationCount)
    }

    @Test
    fun verifyAdminPin_checksExactMatchAndFallback() {
        val manager = ExamSecurityManager(activity = null)
        assertTrue(manager.verifyAdminPin(" 123456 ", "123456"))
        assertFalse(manager.verifyAdminPin("000000", "123456"))
        assertFalse(manager.verifyAdminPin("", "123456"))
        assertTrue(manager.verifyAdminPin("123456", ""))
    }
}

