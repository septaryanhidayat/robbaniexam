package com.ryan.robbaniexam.ui.exam

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import com.ryan.robbaniexam.R
import com.ryan.robbaniexam.data.model.ExamSession
import com.ryan.robbaniexam.data.model.LiveStudentStatus
import com.ryan.robbaniexam.data.model.StudentInfo
import com.ryan.robbaniexam.data.repository.ExamRepository
import com.ryan.robbaniexam.databinding.ActivityExamBinding
import com.ryan.robbaniexam.security.ExamAlarmPlayer
import com.ryan.robbaniexam.security.ExamSecurityManager
import com.ryan.robbaniexam.security.ViolationListener
import com.ryan.robbaniexam.security.ViolationType
import java.util.Locale

class ExamActivity : AppCompatActivity(), ViolationListener {

    private lateinit var binding: ActivityExamBinding
    private lateinit var securityManager: ExamSecurityManager
    private lateinit var studentInfo: StudentInfo
    private lateinit var examSession: ExamSession

    private var countDownTimer: CountDownTimer? = null
    private var commandStreamHandle: ExamRepository.StreamHandle? = null
    private var activeProctorDialog: AlertDialog? = null
    private var isLegalExit: Boolean = false
    private var remainingSeconds: Int = 0
    private var lastReason: String = "Ujian Dimulai"
    private var lastHeartbeatPushAt: Long = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    // Fast 500ms watchdog catches Back + Recent unpin attempts immediately
    private val lockWatchdogRunnable = object : Runnable {
        override fun run() {
            if (isLegalExit || isFinishing || isDestroyed) return

            securityManager.checkAndEnforceUnpinAttempt(
                studentName = studentInfo.name,
                className = studentInfo.className,
                examCode = examSession.examCode
            )

            if (securityManager.checkSplitScreen() && !securityManager.isAdminDialogOpen) {
                securityManager.recordViolation(
                    type = ViolationType.SPLIT_SCREEN_DETECTED,
                    studentName = studentInfo.name,
                    className = studentInfo.className,
                    examCode = examSession.examCode
                )
                securityManager.reorderExamToFront()
            }

            mainHandler.postDelayed(this, 500L)
        }
    }

    // Heartbeat & locked-state fallback poll (instant commands arrive via SSE stream; poll guarantees unlock even if SSE drops)
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (isLegalExit || isFinishing || isDestroyed) return
            val now = System.currentTimeMillis()
            if (now - lastHeartbeatPushAt >= 18000L) {
                val statusText = when {
                    securityManager.isExamLocked -> "TERKUNCI"
                    securityManager.violationCount > 0 -> "PERINGATAN ${securityManager.violationCount}x"
                    else -> "SEDANG UJIAN"
                }
                reportStatusToDashboard(statusText, securityManager.violationCount, lastReason)
            }
            if (securityManager.isExamLocked) {
                ExamRepository.fetchStudentRemoteState(resolvedStudentId()) { remote ->
                    if (remote != null && !isLegalExit && !isFinishing && !isDestroyed) {
                        handleRemoteUpdate(
                            ExamRepository.RemoteCommandUpdate(
                                remoteCommand = remote.remoteCommand,
                                remoteMessage = remote.remoteMessage,
                                status = remote.status,
                                violationCount = remote.violationCount
                            )
                        )
                    }
                }
            }
            val nextDelay = if (securityManager.isExamLocked) 2500L else 20000L
            mainHandler.postDelayed(this, nextDelay)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExamBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }

        val studentJson = intent.getStringExtra("EXTRA_STUDENT_JSON") ?: ""
        val sessionJson = intent.getStringExtra("EXTRA_SESSION_JSON") ?: ""

        studentInfo = try {
            Gson().fromJson(studentJson, StudentInfo::class.java) ?: StudentInfo()
        } catch (_: Exception) {
            StudentInfo(name = "Siswa", className = "7A")
        }

        examSession = try {
            Gson().fromJson(sessionJson, ExamSession::class.java) ?: ExamSession()
        } catch (_: Exception) {
            ExamSession(moodleUrl = "https://elearning.robbani.sch.id")
        }

        val maxAllowed = examSession.maxViolations.coerceAtLeast(1)
        securityManager = ExamSecurityManager(this, maxViolations = maxAllowed, listener = this)
        securityManager.applyExamSecurity()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webViewExam.canGoBack() && !securityManager.isExamLocked) {
                    binding.webViewExam.goBack()
                } else {
                    Toast.makeText(this@ExamActivity, "Tombol Kembali dinonaktifkan saat ujian!", Toast.LENGTH_SHORT).show()
                }
            }
        })

        setupHeaderUi()
        setupWebView()
        startExamTimer(examSession.durationMinutes.coerceAtLeast(1))

        reportStatusToDashboard("SEDANG UJIAN", 0, "Ujian Dimulai")
        startRealtimeCommandStream()
        mainHandler.postDelayed(lockWatchdogRunnable, 1200L)
        mainHandler.postDelayed(heartbeatRunnable, 5000L)

        binding.btnRefreshWeb.setOnClickListener {
            binding.layoutWebError.visibility = View.GONE
            binding.webViewExam.reload()
        }

        binding.btnRetryWeb.setOnClickListener {
            binding.layoutWebError.visibility = View.GONE
            binding.webViewExam.reload()
        }

        binding.btnDismissNotice.setOnClickListener {
            binding.bannerProctorNotice.visibility = View.GONE
        }

        binding.btnExitExam.setOnClickListener {
            showAdminPinDialog(isExitAction = true)
        }

        binding.btnUnlockAdminPin.setOnClickListener {
            showAdminPinDialog(isExitAction = false)
        }
    }

    private fun setupHeaderUi() {
        binding.tvExamTitle.text = examSession.title.ifEmpty { "Ujian Moodle Robbani" }
        val nisSuffix = if (studentInfo.nis.isNotEmpty()) " • ${studentInfo.nis}" else ""
        binding.tvStudentDetail.text = "${studentInfo.name} (${studentInfo.className})$nisSuffix"
        updateViolationBadge(0)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webViewExam, true)

        binding.webViewExam.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                userAgentString = "RobbaniExamBrowser/2.0 (Android Anti-Cheat Secure Environment)"
            }

            setOnLongClickListener { true }
            isHapticFeedbackEnabled = false

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: ""
                    return !(url.startsWith("http://") || url.startsWith("https://"))
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    binding.layoutWebError.visibility = View.GONE
                    injectAntiCopyCss(view)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    injectAntiCopyCss(view)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        val desc = error?.description?.toString() ?: "Koneksi bermasalah"
                        binding.tvWebErrorDetail.text = "Gagal memuat halaman ($desc). Pastikan koneksi internet stabil lalu coba lagi."
                        binding.layoutWebError.visibility = View.VISIBLE
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress in 1..99) {
                        binding.progressWebView.visibility = View.VISIBLE
                        binding.progressWebView.progress = newProgress
                    } else {
                        binding.progressWebView.visibility = View.GONE
                    }
                }
            }

            val rawUrl = examSession.moodleUrl.ifBlank { "https://elearning.robbani.sch.id" }
            val targetUrl = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) rawUrl else "https://$rawUrl"
            loadUrl(targetUrl)
        }
    }

    private fun injectAntiCopyCss(webView: WebView?) {
        val css = "* { -webkit-user-select: none !important; user-select: none !important; -webkit-touch-callout: none !important; }"
        val js = "(function() { " +
            "var parent = document.getElementsByTagName('head').item(0); " +
            "if (parent && !document.getElementById('robbani-anticopy')) { " +
            "var style = document.createElement('style'); " +
            "style.id = 'robbani-anticopy'; " +
            "style.type = 'text/css'; " +
            "style.innerHTML = '$css'; " +
            "parent.appendChild(style); " +
            "} " +
            "document.oncontextmenu = function() { return false; }; " +
            "document.onselectstart = function() { return false; }; " +
            "})()"
        webView?.evaluateJavascript(js, null)
    }

    private fun startExamTimer(durationMinutes: Int) {
        val totalMillis = durationMinutes * 60 * 1000L
        remainingSeconds = durationMinutes * 60
        countDownTimer = object : CountDownTimer(totalMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                remainingSeconds = (millisUntilFinished / 1000L).toInt().coerceAtLeast(0)
                val hours = remainingSeconds / 3600
                val minutes = (remainingSeconds % 3600) / 60
                val seconds = remainingSeconds % 60
                binding.tvTimer.text = String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
            }

            override fun onFinish() {
                remainingSeconds = 0
                binding.tvTimer.text = "00:00:00"
                reportStatusToDashboard("SELESAI", securityManager.violationCount, "Waktu Ujian Habis")
                Toast.makeText(this@ExamActivity, "Waktu Ujian Telah Habis!", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun updateViolationBadge(count: Int) {
        val maxV = examSession.maxViolations.coerceAtLeast(1)
        binding.tvViolationBadge.text = "$count/$maxV"
    }

    private fun resolvedStudentId(): String {
        return studentInfo.studentId.ifEmpty { "STU-${studentInfo.name.hashCode()}" }
    }

    private fun reportStatusToDashboard(statusText: String, violations: Int, reason: String) {
        lastReason = reason
        lastHeartbeatPushAt = System.currentTimeMillis()
        val liveStatus = LiveStudentStatus(
            studentId = resolvedStudentId(),
            studentName = studentInfo.name,
            className = studentInfo.className,
            nis = studentInfo.nis,
            examCode = examSession.examCode,
            status = statusText,
            violationCount = violations,
            lastViolationReason = reason,
            remainingSeconds = remainingSeconds,
            lastUpdated = lastHeartbeatPushAt,
            violationLogs = securityManager.violationHistory.takeLast(15),
            remoteCommand = "",
            remoteMessage = ""
        )
        ExamRepository.pushLiveStudentStatus(liveStatus)
    }

    private fun startRealtimeCommandStream() {
        val sid = resolvedStudentId()
        commandStreamHandle?.stop()
        commandStreamHandle = ExamRepository.startStudentCommandStream(sid) { update ->
            android.util.Log.d("RobbaniSSE", "UI received cmd=${update.remoteCommand} legal=$isLegalExit fin=$isFinishing")
            handleRemoteUpdate(update)
        }
    }

    private fun handleRemoteUpdate(update: ExamRepository.RemoteCommandUpdate) {
        if (isLegalExit || isFinishing || isDestroyed) return
        val sid = resolvedStudentId()

        when (update.remoteCommand.uppercase()) {
            "UNLOCK" -> {
                ExamRepository.clearRemoteCommand(sid)
                performUnlock(sourceMessage = "Layar dibuka oleh Pengawas dari Dashboard")
            }
            "FORCE_FINISH" -> {
                ExamRepository.clearRemoteCommand(sid)
                ExamAlarmPlayer.stopAlarm()
                activeProctorDialog?.dismiss()
                isLegalExit = true
                reportStatusToDashboard("SELESAI", securityManager.violationCount, "Diakhiri oleh Pengawas")
                securityManager.stopLockTaskIfActive()
                Toast.makeText(this, "Ujian diakhiri oleh Pengawas.", Toast.LENGTH_LONG).show()
                finish()
            }
            "MESSAGE" -> {
                ExamRepository.clearRemoteCommand(sid)
                if (update.remoteMessage.isNotBlank()) {
                    showWideProctorMessageModal(update.remoteMessage)
                }
            }
            else -> {
                if (securityManager.isExamLocked && update.status == "SEDANG UJIAN" && update.violationCount == 0) {
                    performUnlock(sourceMessage = "Layar dibuka oleh Pengawas")
                }
            }
        }
    }

    private fun showWideProctorMessageModal(message: String) {
        ExamAlarmPlayer.playWarningBeep(this)
        binding.tvProctorNotice.text = "Pesan Pengawas: $message"
        binding.bannerProctorNotice.visibility = View.VISIBLE

        activeProctorDialog?.dismiss()
        securityManager.isAdminDialogOpen = true

        val view = layoutInflater.inflate(R.layout.dialog_proctor_message, null)
        view.findViewById<TextView>(R.id.tvProctorModalMessage).text = message
        val btnOk = view.findViewById<MaterialButton>(R.id.btnAcknowledgeNotice)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(false)
            .create()

        dialog.setOnDismissListener {
            activeProctorDialog = null
            securityManager.isAdminDialogOpen = false
            if (!isLegalExit) {
                securityManager.enableImmersiveMode()
            }
        }

        btnOk.setOnClickListener {
            dialog.dismiss()
        }

        activeProctorDialog = dialog
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            val width = (resources.displayMetrics.widthPixels * 0.92).toInt()
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun performUnlock(sourceMessage: String) {
        ExamAlarmPlayer.stopAlarm()
        securityManager.unlockExam()
        binding.layoutLockedScreen.visibility = View.GONE
        updateViolationBadge(0)
        securityManager.enableImmersiveMode()
        securityManager.startLockTaskIfSupported()
        reportStatusToDashboard("SEDANG UJIAN", 0, sourceMessage)
        Toast.makeText(this, sourceMessage, Toast.LENGTH_SHORT).show()
    }

    override fun onViolationDetected(type: ViolationType, message: String, currentViolations: Int, maxViolations: Int) {
        updateViolationBadge(currentViolations)
        ExamAlarmPlayer.playWarningBeep(this)
        reportStatusToDashboard("PERINGATAN ${currentViolations}x", currentViolations, message)
        Toast.makeText(this, "PERINGATAN ($currentViolations/$maxViolations): $message", Toast.LENGTH_LONG).show()
    }

    override fun onMaxViolationsExceeded(type: ViolationType, message: String) {
        val maxV = examSession.maxViolations.coerceAtLeast(1)
        updateViolationBadge(maxV)
        ExamAlarmPlayer.playEmergencySiren(this, durationMs = 4500L)
        reportStatusToDashboard("TERKUNCI", maxV, message)
        binding.layoutLockedScreen.visibility = View.VISIBLE
        binding.tvLockReason.text = "$message\n\nAlarm berbunyi dan pelanggaran telah dilaporkan. Minta Pengawas membuka kunci lewat Dashboard atau PIN Guru."
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isLegalExit && !securityManager.isAdminDialogOpen) {
            if (!securityManager.isExamLocked) {
                securityManager.recordViolation(
                    type = ViolationType.APP_SWITCHED,
                    customMessage = "Percobaan keluar / Home / Recent Apps",
                    studentName = studentInfo.name,
                    className = studentInfo.className,
                    examCode = examSession.examCode
                )
            }
            securityManager.reorderExamToFront()
        }
    }

    override fun onPause() {
        super.onPause()
        if (!isLegalExit && !securityManager.isAdminDialogOpen) {
            securityManager.reorderExamToFront()
            mainHandler.postDelayed({
                if (!isLegalExit && !securityManager.isAdminDialogOpen) {
                    securityManager.reorderExamToFront()
                }
            }, 180)
            mainHandler.postDelayed({
                if (!isLegalExit && !securityManager.isAdminDialogOpen) {
                    securityManager.reorderExamToFront()
                }
            }, 450)
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isLegalExit && !securityManager.isAdminDialogOpen) {
            if (!securityManager.isExamLocked) {
                securityManager.recordViolation(
                    type = ViolationType.APP_SWITCHED,
                    customMessage = "Aplikasi ditinggalkan (Recent / Back / Home)",
                    studentName = studentInfo.name,
                    className = studentInfo.className,
                    examCode = examSession.examCode
                )
            }
            securityManager.reorderExamToFront()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            securityManager.enableImmersiveMode()
            securityManager.startLockTaskIfSupported()
        } else if (!isLegalExit && !securityManager.isAdminDialogOpen) {
            securityManager.reorderExamToFront()
            if (!securityManager.isExamLocked) {
                securityManager.recordViolation(
                    type = ViolationType.APP_SWITCHED,
                    customMessage = "Aplikasi kehilangan fokus layar.",
                    studentName = studentInfo.name,
                    className = studentInfo.className,
                    examCode = examSession.examCode
                )
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_APP_SWITCH) {
            Toast.makeText(this, "Tombol navigasi dinonaktifkan saat ujian!", Toast.LENGTH_SHORT).show()
            securityManager.startLockTaskIfSupported()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showAdminPinDialog(isExitAction: Boolean) {
        securityManager.isAdminDialogOpen = true

        val dialogView = layoutInflater.inflate(R.layout.dialog_admin_pin, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvPinDialogTitle)
        val tvSubtitle = dialogView.findViewById<TextView>(R.id.tvPinDialogSubtitle)
        val etPin = dialogView.findViewById<EditText>(R.id.etAdminPin)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelPin)
        val btnConfirm = dialogView.findViewById<MaterialButton>(R.id.btnConfirmPin)

        if (isExitAction) {
            tvTitle.text = "Keluar dari Ujian"
            tvSubtitle.text = "Masukkan PIN Pengawas untuk menyelesaikan & keluar ujian"
            btnConfirm.text = "Keluar Ujian"
        } else {
            tvTitle.text = "Buka Kunci Layar"
            tvSubtitle.text = "Masukkan PIN Pengawas untuk membuka kembali ujian siswa"
            btnConfirm.text = "Buka Kunci"
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.setOnDismissListener {
            securityManager.isAdminDialogOpen = false
            if (!isLegalExit) {
                securityManager.enableImmersiveMode()
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            val enteredPin = etPin.text.toString()
            if (securityManager.verifyAdminPin(enteredPin, examSession.adminPin)) {
                dialog.dismiss()
                if (isExitAction) {
                    ExamAlarmPlayer.stopAlarm()
                    isLegalExit = true
                    reportStatusToDashboard("SELESAI", securityManager.violationCount, "Siswa Keluar dengan PIN Guru")
                    securityManager.stopLockTaskIfActive()
                    finish()
                } else {
                    performUnlock("Buka Kunci PIN Guru")
                }
            } else {
                Toast.makeText(this, getString(R.string.wrong_admin_pin), Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        commandStreamHandle?.stop()
        activeProctorDialog?.dismiss()
        ExamAlarmPlayer.stopAlarm()
        countDownTimer?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
    }
}
