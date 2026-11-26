package com.ryan.robbaniexam.security

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ryan.robbaniexam.data.model.ViolationLog
import com.ryan.robbaniexam.ui.exam.ExamActivity

class ExamSecurityManager(
    private val activity: Activity?,
    private val maxViolations: Int = 3,
    private val listener: ViolationListener? = null,
    private val violationCooldownMs: Long = 1500L,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    var violationCount: Int = 0
        private set

    var isExamLocked: Boolean = false
        private set

    var isAdminDialogOpen: Boolean = false

    var wasLockTaskActive: Boolean = false
        private set

    private var lastViolationTimestamp: Long = 0L
    private val _violationHistory = mutableListOf<ViolationLog>()
    val violationHistory: List<ViolationLog> get() = _violationHistory.toList()

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun applyExamSecurity() {
        enableScreenshotProtection()
        enableImmersiveMode()
        enableKeepScreenOn()
        startLockTaskIfSupported()
    }

    fun enableScreenshotProtection() {
        val act = activity ?: return
        act.window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
    }

    fun enableImmersiveMode() {
        val act = activity ?: return
        WindowCompat.setDecorFitsSystemWindows(act.window, false)
        val controller = WindowInsetsControllerCompat(act.window, act.window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    fun enableKeepScreenOn() {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun isLockTaskCurrentlyActive(): Boolean {
        val act = activity ?: return false
        return try {
            val am = act.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val state = am?.lockTaskModeState ?: ActivityManager.LOCK_TASK_MODE_NONE
            val active = state != ActivityManager.LOCK_TASK_MODE_NONE
            if (active) {
                wasLockTaskActive = true
            }
            active
        } catch (_: Exception) {
            false
        }
    }

    fun startLockTaskIfSupported() {
        val act = activity ?: return
        try {
            if (!isLockTaskCurrentlyActive()) {
                act.startLockTask()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Detects if student escaped Screen Pinning via Back + Recent combination or gesture.
     * Immediately re-pins, pulls app to front, and records a violation.
     */
    fun checkAndEnforceUnpinAttempt(
        studentName: String,
        className: String,
        examCode: String
    ): Boolean {
        val act = activity ?: return false
        if (isAdminDialogOpen || act.isFinishing || act.isDestroyed) return false

        val currentlyPinned = isLockTaskCurrentlyActive()
        if (wasLockTaskActive && !currentlyPinned) {
            recordViolation(
                type = ViolationType.APP_SWITCHED,
                customMessage = "Percobaan melepas kunci layar (Back + Recent)",
                studentName = studentName,
                className = className,
                examCode = examCode
            )
            reorderExamToFront()
            return true
        }
        return false
    }

    /**
     * Re-brings the Exam Activity immediately to the top if user tries to unpin or press Home/Recents.
     */
    fun reorderExamToFront() {
        val act = activity ?: return
        if (isAdminDialogOpen || act.isFinishing || act.isDestroyed) return

        try {
            startLockTaskIfSupported()
            val am = act.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.moveTaskToFront(act.taskId, ActivityManager.MOVE_TASK_WITH_HOME)

            val intent = Intent(act, ExamActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            act.startActivity(intent)

            mainHandler.postDelayed({
                if (!isAdminDialogOpen && !act.isFinishing && !act.isDestroyed) {
                    try {
                        startLockTaskIfSupported()
                        am?.moveTaskToFront(act.taskId, ActivityManager.MOVE_TASK_WITH_HOME)
                        enableImmersiveMode()
                    } catch (_: Exception) {
                    }
                }
            }, 220)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopLockTaskIfActive() {
        val act = activity ?: return
        try {
            wasLockTaskActive = false
            act.stopLockTask()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun canDrawOverlays(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

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

    fun isNetworkConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun checkSplitScreen(): Boolean {
        return activity?.isInMultiWindowMode == true
    }

    fun recordViolation(
        type: ViolationType,
        customMessage: String? = null,
        studentName: String = "",
        className: String = "",
        examCode: String = ""
    ): Boolean {
        if (isExamLocked || isAdminDialogOpen) return false

        val now = timeProvider()
        if (lastViolationTimestamp > 0L && (now - lastViolationTimestamp) < violationCooldownMs) {
            return false
        }
        lastViolationTimestamp = now

        violationCount++
        val msg = customMessage ?: type.defaultMessage
        _violationHistory.add(
            ViolationLog(
                studentName = studentName,
                className = className,
                examCode = examCode,
                violationType = type.title,
                timestamp = now,
                details = msg
            )
        )

        if (violationCount >= maxViolations) {
            isExamLocked = true
            listener?.onMaxViolationsExceeded(type, msg)
        } else {
            listener?.onViolationDetected(type, msg, violationCount, maxViolations)
        }
        return true
    }

    fun verifyAdminPin(enteredPin: String, correctPin: String): Boolean {
        val expected = correctPin.trim().ifEmpty { "123456" }
        return enteredPin.trim().isNotEmpty() && enteredPin.trim() == expected
    }

    fun unlockExam() {
        isExamLocked = false
        violationCount = 0
        lastViolationTimestamp = 0L
    }
}
