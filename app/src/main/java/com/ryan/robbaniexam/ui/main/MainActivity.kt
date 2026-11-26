package com.ryan.robbaniexam.ui.main

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.ryan.robbaniexam.data.model.ExamSession
import com.ryan.robbaniexam.data.model.StudentInfo
import com.ryan.robbaniexam.data.repository.ExamRepository
import com.ryan.robbaniexam.databinding.ActivityMainBinding
import com.ryan.robbaniexam.ui.exam.ExamActivity
import com.ryan.robbaniexam.ui.scan.QrScanActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var scannedExamSession: ExamSession? = null

    private val availableClasses = arrayOf(
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
                    Toast.makeText(this, "QR Code Berhasil: ${parsedSession.title}", Toast.LENGTH_SHORT).show()
                } else {
                    binding.etExamCode.setText(qrCodeData)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("robbani_student_prefs", Context.MODE_PRIVATE)

        // Restore saved student profile
        val savedName = prefs.getString("KEY_STUDENT_NAME", "")
        val savedClass = prefs.getString("KEY_STUDENT_CLASS", "")
        if (!savedName.isNullOrEmpty()) binding.etStudentName.setText(savedName)

        val classAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, availableClasses)
        binding.actClass.setAdapter(classAdapter)
        if (!savedClass.isNullOrEmpty()) binding.actClass.setText(savedClass, false)

        binding.btnScanQr.setOnClickListener {
            val intent = Intent(this, QrScanActivity::class.java)
            qrScanLauncher.launch(intent)
        }

        binding.btnStartExam.setOnClickListener {
            val name = binding.etStudentName.text.toString().trim()
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
                binding.etExamCode.error = "Kode Ujian / Link Moodle wajib diisi"
                return@setOnClickListener
            }

            // Save student profile for seamless next logins
            prefs.edit().apply {
                putString("KEY_STUDENT_NAME", name)
                putString("KEY_STUDENT_CLASS", className)
                apply()
            }

            val student = StudentInfo(
                studentId = "STU-${System.currentTimeMillis().toString().takeLast(6)}",
                name = name,
                className = className,
                nis = ""
            )

            // Determine final ExamSession object with exact Moodle URL from Admin Dashboard or Input
            val sessionToUse = if (scannedExamSession != null && (scannedExamSession?.examCode.equals(codeOrUrl, ignoreCase = true) || codeOrUrl.isEmpty())) {
                scannedExamSession!!
            } else {
                ExamRepository.getExamSessionByInput(codeOrUrl)
            }

            val intent = Intent(this, ExamActivity::class.java).apply {
                putExtra("EXTRA_STUDENT_JSON", Gson().toJson(student))
                putExtra("EXTRA_SESSION_JSON", Gson().toJson(sessionToUse))
            }
            startActivity(intent)
        }
    }
}
