package com.ryan.robbaniexam.ui.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.ryan.robbaniexam.R
import com.ryan.robbaniexam.data.model.ExamSession
import com.ryan.robbaniexam.data.model.StudentInfo
import com.ryan.robbaniexam.data.repository.ExamRepository
import com.ryan.robbaniexam.databinding.ActivityMainBinding
import com.ryan.robbaniexam.security.ExamAlarmPlayer
import com.ryan.robbaniexam.security.ExamSecurityManager
import com.ryan.robbaniexam.ui.exam.ExamActivity
import com.ryan.robbaniexam.ui.scan.QrScanActivity
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var securityManager: ExamSecurityManager
    private var scannedExamSession: ExamSession? = null
    private var announcementStreamHandle: ExamRepository.StreamHandle? = null

    private val defaultClasses = listOf(
        "7A", "7B", "7C",
        "8A", "8B", "8C",
        "9A", "9B", "9C",
        "X IPA 1", "X IPA 2", "X IPS 1", "X IPS 2",
        "XI IPA 1", "XI IPA 2", "XI IPS 1", "XI IPS 2",
        "XII IPA 1", "XII IPA 2", "XII IPS 1", "XII IPS 2"
    )

    private val qrScanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val qrCodeData = result.data?.getStringExtra("EXTRA_QR_RESULT")
            if (!qrCodeData.isNullOrBlank()) {
                val parsedSession = ExamRepository.parseExamSessionFromQr(qrCodeData)
                if (parsedSession != null && parsedSession.moodleUrl.isNotEmpty()) {
                    scannedExamSession = parsedSession
                    binding.etExamCode.setText(parsedSession.examCode)
                    showExamPreview(parsedSession)
                    Toast.makeText(this, "QR Berhasil: ${parsedSession.title}", Toast.LENGTH_SHORT).show()
                } else {
                    binding.etExamCode.setText(qrCodeData)
                    hideExamPreview()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        securityManager = ExamSecurityManager(this)
        val prefs = getSharedPreferences("robbani_student_prefs", Context.MODE_PRIVATE)

        val savedName = prefs.getString("KEY_STUDENT_NAME", "")
        val savedNis = prefs.getString("KEY_STUDENT_NIS", "")
        val savedClass = prefs.getString("KEY_STUDENT_CLASS", "")
        if (!savedName.isNullOrEmpty()) binding.etStudentName.setText(savedName)
        if (!savedNis.isNullOrEmpty()) binding.etStudentNis.setText(savedNis)

        val classAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, defaultClasses.toMutableList())
        binding.actClass.setAdapter(classAdapter)
        if (!savedClass.isNullOrEmpty()) binding.actClass.setText(savedClass, false)

        updateReadinessStatus()
        loadGlobalAnnouncement()
        loadDynamicClasses(classAdapter)

        binding.tvSecurityChip.setOnClickListener {
            if (!securityManager.canDrawOverlays(this)) {
                openOverlayPermissionSettings()
            }
        }

        binding.btnScanQr.setOnClickListener {
            qrScanLauncher.launch(Intent(this, QrScanActivity::class.java))
        }

        binding.btnTestAlarm.setOnClickListener {
            ExamAlarmPlayer.playWarningBeep(this)
            Toast.makeText(this, "Tes suara alarm & getar aktif!", Toast.LENGTH_SHORT).show()
        }

        binding.btnStartExam.setOnClickListener {
            val name = binding.etStudentName.text.toString().trim()
            val nis = binding.etStudentNis.text.toString().trim()
            val className = binding.actClass.text.toString().trim()
            val codeOrUrl = binding.etExamCode.text.toString().trim()

            if (name.isEmpty()) {
                binding.etStudentName.error = "Nama siswa wajib diisi"
                return@setOnClickListener
            }
            if (className.isEmpty()) {
                binding.actClass.error = "Pilih / ketik kelas"
                return@setOnClickListener
            }
            if (codeOrUrl.isEmpty()) {
                binding.etExamCode.error = "Kode Ujian / Link Ujian wajib diisi"
                return@setOnClickListener
            }

            if (!securityManager.isNetworkConnected(this)) {
                Toast.makeText(this, "Tidak ada koneksi internet! Aktifkan Wi-Fi atau data seluler.", Toast.LENGTH_LONG).show()
                updateReadinessStatus()
                return@setOnClickListener
            }

            var stableId = prefs.getString("KEY_STUDENT_DEVICE_ID", null)
            if (stableId.isNullOrBlank()) {
                stableId = "STU-" + UUID.randomUUID().toString().take(8).uppercase()
            }

            prefs.edit().apply {
                putString("KEY_STUDENT_NAME", name)
                putString("KEY_STUDENT_NIS", nis)
                putString("KEY_STUDENT_CLASS", className)
                putString("KEY_STUDENT_DEVICE_ID", stableId)
                apply()
            }

            val student = StudentInfo(
                studentId = stableId,
                name = name,
                className = className,
                nis = nis
            )

            val proceedStart = {
                val localScanned = scannedExamSession
                if (localScanned != null && localScanned.examCode.equals(codeOrUrl, ignoreCase = true)) {
                    launchExam(student, localScanned)
                } else {
                    setLoadingState(true)
                    ExamRepository.resolveExamSessionAsync(codeOrUrl) { resolvedSession, fromFirebase ->
                        setLoadingState(false)
                        if (fromFirebase) {
                            showExamPreview(resolvedSession)
                        }
                        launchExam(student, resolvedSession)
                    }
                }
            }

            // Recommend enabling Display Over Other Apps so Recent+Back cannot leave the exam unlocked
            if (!securityManager.canDrawOverlays(this)) {
                AlertDialog.Builder(this)
                    .setTitle("Aktifkan Kunci Layar Penuh")
                    .setMessage("Izinkan 'Tampilkan di atas aplikasi lain' agar aplikasi tetap mengunci layar jika tombol Recent + Back ditekan.")
                    .setPositiveButton("Aktifkan Izin") { _, _ ->
                        openOverlayPermissionSettings()
                    }
                    .setNegativeButton("Lanjutkan Ujian") { _, _ ->
                        proceedStart()
                    }
                    .show()
            } else {
                proceedStart()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateReadinessStatus()
        loadGlobalAnnouncement()
        startAnnouncementStream()
    }

    override fun onPause() {
        super.onPause()
        announcementStreamHandle?.stop()
        announcementStreamHandle = null
    }

    override fun onDestroy() {
        super.onDestroy()
        announcementStreamHandle?.stop()
        announcementStreamHandle = null
    }

    private fun loadDynamicClasses(adapter: ArrayAdapter<String>) {
        ExamRepository.fetchAvailableClasses { remoteClasses ->
            if (!isFinishing && !isDestroyed && remoteClasses.isNotEmpty()) {
                adapter.clear()
                adapter.addAll(remoteClasses)
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun openOverlayPermissionSettings() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Buka Pengaturan -> Aplikasi -> Robbani Exam -> Tampilkan di atas aplikasi lain", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateReadinessStatus() {
        val devActive = securityManager.checkDeveloperOptions(this)
        val overlayReady = securityManager.canDrawOverlays(this)

        when {
            devActive -> {
                binding.tvSecurityChip.text = "USB Debug Aktif"
                binding.tvSecurityChip.setTextColor(ContextCompat.getColor(this, R.color.robbani_yellow))
            }
            !overlayReady -> {
                binding.tvSecurityChip.text = "Aktifkan Izin Kunci"
                binding.tvSecurityChip.setTextColor(ContextCompat.getColor(this, R.color.robbani_yellow))
            }
            else -> {
                binding.tvSecurityChip.text = "Kunci Penuh Siap"
                binding.tvSecurityChip.setTextColor(0xFF6EE7B7.toInt())
            }
        }

        val online = securityManager.isNetworkConnected(this)
        if (online) {
            binding.tvNetworkChip.text = "Koneksi Online"
            binding.tvNetworkChip.setTextColor(0xFF7DD3FC.toInt())
        } else {
            binding.tvNetworkChip.text = "Offline"
            binding.tvNetworkChip.setTextColor(0xFFFCA5A5.toInt())
        }
    }

    private fun loadGlobalAnnouncement() {
        ExamRepository.fetchBroadcastAnnouncement { message ->
            if (!isFinishing && !isDestroyed) {
                applyAnnouncementBanner(message ?: "")
            }
        }
    }

    private fun startAnnouncementStream() {
        announcementStreamHandle?.stop()
        announcementStreamHandle = ExamRepository.startAnnouncementStream { state ->
            if (!isFinishing && !isDestroyed) {
                applyAnnouncementBanner(state.message)
            }
        }
    }

    private fun applyAnnouncementBanner(message: String) {
        if (message.isNotBlank()) {
            binding.tvAnnouncementText.text = message
            binding.cardAnnouncement.visibility = View.VISIBLE
        } else {
            binding.cardAnnouncement.visibility = View.GONE
        }
    }

    private fun showExamPreview(session: ExamSession) {
        binding.tvPreviewExamTitle.text = session.title.ifEmpty { "Sesi Ujian Siap" }
        val subjectText = if (session.subject.isNotEmpty()) "${session.subject} • " else ""
        binding.tvPreviewExamMeta.text =
            "${subjectText}Kode: ${session.examCode} • Durasi ${session.durationMinutes} menit • Batas ${session.maxViolations}x"
        binding.layoutExamPreview.visibility = View.VISIBLE
    }

    private fun hideExamPreview() {
        binding.layoutExamPreview.visibility = View.GONE
    }

    private fun setLoadingState(loading: Boolean) {
        binding.btnStartExam.isEnabled = !loading
        binding.btnStartExam.text = if (loading) "Memverifikasi Kode Ujian..." else getString(R.string.btn_start_exam)
    }

    private fun launchExam(student: StudentInfo, session: ExamSession) {
        val intent = Intent(this, ExamActivity::class.java).apply {
            putExtra("EXTRA_STUDENT_JSON", Gson().toJson(student))
            putExtra("EXTRA_SESSION_JSON", Gson().toJson(session))
        }
        startActivity(intent)
    }
}
