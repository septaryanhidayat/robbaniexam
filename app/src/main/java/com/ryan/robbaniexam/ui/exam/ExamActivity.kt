package com.ryan.robbaniexam.ui.exam

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.CountDownTimer
import android.view.KeyEvent
import android.view.View
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
import com.ryan.robbaniexam.data.model.StudentInfo
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExamBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

        // Initialize Security Manager
        securityManager = ExamSecurityManager(this, maxViolations = examSession.maxViolations, listener = this)
        securityManager.applyExamSecurity()

        setupHeaderUi()
        setupWebView()
        startExamTimer(examSession.durationMinutes)

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
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                allowFileAccess = false
                allowContentAccess = false
                userAgentString = "RobbaniExamBrowser/1.0 (Android Anti-Cheat Secure Environment)"
            }

            // Disable Long Click & Copy-Paste Selection
            setOnLongClickListener { true }
            isHapticFeedbackEnabled = false

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: ""
                    // Prevent leaving Moodle domain
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        return false // Allow loading inside WebView
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

            // Load Moodle URL
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
                binding.tvTimer.text = String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
            }

            override fun onFinish() {
                binding.tvTimer.text = "00:00:00"
                Toast.makeText(this@ExamActivity, "Waktu Ujian Telah Habis!", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun updateViolationBadge(count: Int) {
        binding.tvViolationBadge.text = "Peringatan: $count/${examSession.maxViolations}"
    }

    override fun onViolationDetected(type: ViolationType, message: String, currentViolations: Int, maxViolations: Int) {
        updateViolationBadge(currentViolations)
        Toast.makeText(this, "PERINGATAN ($currentViolations/$maxViolations): $message", Toast.LENGTH_LONG).show()
    }

    override fun onMaxViolationsExceeded(type: ViolationType, message: String) {
        updateViolationBadge(examSession.maxViolations)
        binding.layoutLockedScreen.visibility = View.VISIBLE
        binding.tvLockReason.text = "LAYAR TERKUNCI ($message)\nMinta Pengawas / Guru untuk membuka kunci menggunakan PIN Admin."
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isLegalExit && !securityManager.isExamLocked) {
            securityManager.recordViolation(ViolationType.APP_SWITCHED)
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
        } else if (!isLegalExit && !securityManager.isExamLocked) {
            securityManager.recordViolation(ViolationType.APP_SWITCHED, "Aplikasi kehilangan fokus layar.")
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // Block Back Key during exam
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
                    securityManager.stopLockTaskIfActive()
                    finish()
                } else {
                    securityManager.unlockExam()
                    binding.layoutLockedScreen.visibility = View.GONE
                    updateViolationBadge(0)
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
