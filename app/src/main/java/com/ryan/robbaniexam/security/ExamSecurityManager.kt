package com.ryan.robbaniexam.security

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ryan.robbaniexam.ui.exam.ExamActivity

class ExamSecurityManager(
    private val activity: Activity,
    private val maxViolations: Int = 3,
    private val listener: ViolationListener? = null
) {
    var violationCount: Int = 0
        private set

    var isExamLocked: Boolean = false
        private set

    fun applyExamSecurity() {
        enableScreenshotProtection()
        enableImmersiveMode()
        enableKeepScreenOn()
        startLockTaskIfSupported()
    }

    /**
     * Prevents screenshots, screen recording, and task switcher preview
     */
    fun enableScreenshotProtection() {
        activity.window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
    }

    /**
     * Hides system status bar and navigation bar in sticky immersive mode
     */
    fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /**
     * Keeps screen turned on during exam
     */
    fun enableKeepScreenOn() {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Starts LockTask mode (Screen Pinning) to lock Home, Recents, and Back gestures
     */
    fun startLockTaskIfSupported() {
        try {
            activity.startLockTask()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Re-brings the Exam Activity immediately to the top if user presses Home or Recents
     */
    fun reorderExamToFront() {
        try {
            val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.moveTaskToFront(activity.taskId, ActivityManager.MOVE_TASK_WITH_HOME)

            val intent = Intent(activity, ExamActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Stops LockTask mode when exiting exam legally with Admin PIN
     */
    fun stopLockTaskIfActive() {
        try {
            activity.stopLockTask()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Checks if Developer Options or USB Debugging are enabled on device
     */
    fun checkDeveloperOptions(context: Context): Boolean {
        val devEnabled = Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            0
        ) != 0

        val adbEnabled = Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.ADB_ENABLED,
            0
        ) != 0

        return devEnabled || adbEnabled
    }

    /**
     * Checks if activity is currently in split-screen / multi-window mode
     */
    fun checkSplitScreen(): Boolean {
        return activity.isInMultiWindowMode
    }

    /**
     * Triggered when a violation occurs (e.g. app switch, home/recents pressed)
     */
    fun recordViolation(type: ViolationType, customMessage: String? = null) {
        if (isExamLocked) return

        violationCount++
        val msg = customMessage ?: type.defaultMessage

        if (violationCount >= maxViolations) {
            isExamLocked = true
            listener?.onMaxViolationsExceeded(type, msg)
        } else {
            listener?.onViolationDetected(type, msg, violationCount, maxViolations)
        }
    }

    /**
     * Verifies if entered PIN matches Admin / Teacher exit PIN
     */
    fun verifyAdminPin(enteredPin: String, correctPin: String): Boolean {
        return enteredPin.trim() == correctPin.trim()
    }

    /**
     * Unlocks the exam after admin PIN input
     */
    fun unlockExam() {
        isExamLocked = false
        violationCount = 0
    }
}
