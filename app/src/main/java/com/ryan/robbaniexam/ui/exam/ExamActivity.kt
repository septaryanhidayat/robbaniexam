package com.ryan.robbaniexam.ui.exam

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
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
    private var isLegalExit: Boolean = false
    private var formattedRemainingTime: String = "00:00:00"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExamBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }

        val studentJson = intent.getStringExtra("EXTRA_STUDENT_JSON") ?: ""
        val sessionJson = intent.getStringExtra("EXTRA_SESSION_JSON") ?: ""

        studentInfo = try {
            Gson().fromJson(studentJson, StudentInfo::class.java) ?: StudentInfo()
        } catch (e: Exception) {
            StudentInfo(name = "Siswa", className = "7A")
        }

        examSession = try {
            Gson().fromJson(sessionJson, ExamSession::class.java) ?: ExamSession()
        } catch (e: Exception) {
            ExamSession(moodleUrl = "https://elearning.robbani.sch.id")
        }

        securityManager = ExamSecurityManager(this, maxViolations = examSession.maxViolations, listener = this)
        securityManager.applyExamSecurity()

        setupHeaderUi()
        setupWebView()
        startExamTimer(examSession.durationMinutes)

        // Push initial status to live Dashboard Admin
        reportStatusToDashboard("SEDANG UJIAN", 0, "Ujian Dimulai")

        binding.btnExitExam.setOnClickListener {
            showAdminPinDialog(isExitAction = true)
        }

        binding.btnUnlockAdminPin.setOnClickListener {
            showAdminPinDialog(isExitAction = false)
        }
    }

    private fun setupHeaderUi() {
        binding.tvExamTitle.text = examSession.title.ifEmpty { "Ujian Moodle Robbani" }
        binding.tvStudentDetail.text = "${studentInfo.name} (${studentInfo.className})"
        updateViolationBadge(0)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
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
                userAgentString = "RobbaniExamBrowser/1.0 (Android Anti-Cheat Secure Environment)"
            }

            setOnLongClickListener { true }
            isHapticFeedbackEnabled = false

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: ""
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        return false
                    }
                    return true
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    injectAntiCopyCss(view)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    injectAntiCopyCss(view)
                }
            }

            webChromeClient = WebChromeClient()

            val targetUrl = if (examSession.moodleUrl.startsWith("http")) examSession.moodleUrl else "https://${examSession.moodleUrl}"
            loadUrl(targetUrl)
        }
    }

    private fun injectAntiCopyCss(webView: WebView?) {
        val css = "* { -webkit-user-select: none !important; user-select: none !important; -webkit-touch-callout: none !important; }"
        val js = "javascript:(function() { " +
                "var parent = document.getElementsByTagName('head').item(0); " +
                "var style = document.createElement('style'); " +
                "style.type = 'text/css'; " +
                "style.innerHTML = '$css'; " +
                "parent.appendChild(style); " +
                "document.oncontextmenu = function() { return false; }; " +
                "document.onselectstart = function() { return false; }; " +
                "})()"
        webView?.evaluateJavascript(js, null)
    }

    private fun startExamTimer(durationMinutes: Int) {
        val totalMillis = durationMinutes * 60 * 1000L
        countDownTimer = object : CountDownTimer(totalMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val hours = (millisUntilFinished / 1000) / 3600
                val minutes = ((millisUntilFinished / 1000) % 3600) / 60
                val seconds = (millisUntilFinished / 1000) % 60
                formattedRemainingTime = String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
                binding.tvTimer.text = formattedRemainingTime
            }

            override fun onFinish() {
                formattedRemainingTime = "00:00:00"
                binding.tvTimer.text = formattedRemainingTime
                reportStatusToDashboard("SELESAI", securityManager.violationCount, "Waktu Ujian Habis")
                Toast.makeText(this@ExamActivity, "Waktu Ujian Telah Habis!", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun updateViolationBadge(count: Int) {
        binding.tvViolationBadge.text = "Peringatan: $count/${examSession.maxViolations}"
    }

    private fun reportStatusToDashboard(statusText: String, violations: Int, reason: String) {
        val liveStatus = LiveStudentStatus(
            studentId = studentInfo.studentId.ifEmpty { "STU-${studentInfo.name.hashCode()}" },
            studentName = studentInfo.name,
            className = studentInfo.className,
            examCode = examSession.examCode,
            status = statusText,
            violationCount = violations,
            lastViolationReason = reason,
            remainingSeconds = 0,
            lastUpdated = System.currentTimeMillis()
        )
        ExamRepository.pushLiveStudentStatus(liveStatus)
    }

    override fun onViolationDetected(type: ViolationType, message: String, currentViolations: Int, maxViolations: Int) {
        updateViolationBadge(currentViolations)
        reportStatusToDashboard("PERINGATAN ${currentViolations}x", currentViolations, message)
        Toast.makeText(this, "PERINGATAN ($currentViolations/$maxViolations): $message", Toast.LENGTH_LONG).show()
    }

    override fun onMaxViolationsExceeded(type: ViolationType, message: String) {
        updateViolationBadge(examSession.maxViolations)
        reportStatusToDashboard("TERKUNCI", examSession.maxViolations, message)
        binding.layoutLockedScreen.visibility = View.VISIBLE
        binding.tvLockReason.text = "LAYAR TERKUNCI ($message)\nMinta Pengawas / Guru untuk membuka kunci menggunakan PIN Admin."
    }

    /**
     * Intercept Home Button / Recent Apps Gesture -> Immediately re-launch ExamActivity onto screen!
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isLegalExit) {
            if (!securityManager.isExamLocked) {
                securityManager.recordViolation(ViolationType.APP_SWITCHED, "Menekan Tombol Home / Recent Apps")
            }
            securityManager.reorderExamToFront()
        }
    }

    override fun onPause() {
        super.onPause()
        if (!isLegalExit) {
            securityManager.reorderExamToFront()
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean) {
        super.onMultiWindowModeChanged(isInMultiWindowMode)
        if (isInMultiWindowMode && !isLegalExit) {
            securityManager.recordViolation(ViolationType.SPLIT_SCREEN_DETECTED)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            securityManager.enableImmersiveMode()
        } else if (!isLegalExit) {
            securityManager.reorderExamToFront()
            if (!securityManager.isExamLocked) {
                securityManager.recordViolation(ViolationType.APP_SWITCHED, "Aplikasi kehilangan fokus layar.")
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            Toast.makeText(this, "Tombol Kembali Dinonaktifkan Saat Ujian!", Toast.LENGTH_SHORT).show()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showAdminPinDialog(isExitAction: Boolean) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_admin_pin, null)
        val etPin = dialogView.findViewById<EditText>(R.id.etAdminPin)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelPin)
        val btnConfirm = dialogView.findViewById<MaterialButton>(R.id.btnConfirmPin)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
            securityManager.enableImmersiveMode()
        }

        btnConfirm.setOnClickListener {
            val enteredPin = etPin.text.toString()
            if (securityManager.verifyAdminPin(enteredPin, examSession.adminPin)) {
                dialog.dismiss()
                if (isExitAction) {
                    isLegalExit = true
                    reportStatusToDashboard("SELESAI", securityManager.violationCount, "Siswa Keluar dengan PIN Guru")
                    securityManager.stopLockTaskIfActive()
                    finish()
                } else {
                    securityManager.unlockExam()
                    binding.layoutLockedScreen.visibility = View.GONE
                    updateViolationBadge(0)
                    reportStatusToDashboard("SEDANG UJIAN", 0, "Buka Kunci PIN Guru")
                    Toast.makeText(this, "Ujian Berhasil Dibuka Kunci", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, getString(R.string.wrong_admin_pin), Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
    }
}
