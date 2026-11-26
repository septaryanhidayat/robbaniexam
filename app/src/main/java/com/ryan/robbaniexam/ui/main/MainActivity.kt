package com.ryan.robbaniexam.ui.main

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.ryan.robbaniexam.data.model.StudentInfo
import com.ryan.robbaniexam.data.repository.ExamRepository
import com.ryan.robbaniexam.databinding.ActivityMainBinding
import com.ryan.robbaniexam.ui.exam.ExamActivity
import com.ryan.robbaniexam.ui.scan.QrScanActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

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
                val session = ExamRepository.parseExamSessionFromQr(qrCodeData!!)
                if (session != null) {
                    binding.etExamCode.setText(session.examCode)
                    Toast.makeText(this, "QR Code Berhasil Dipindai: ${session.title}", Toast.LENGTH_SHORT).show()
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

        val classAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, availableClasses)
        binding.actClass.setAdapter(classAdapter)

        binding.btnScanQr.setOnClickListener {
            val intent = Intent(this, QrScanActivity::class.java)
            qrScanLauncher.launch(intent)
        }

        binding.btnStartExam.setOnClickListener {
            val name = binding.etStudentName.text.toString().trim()
            val className = binding.actClass.text.toString().trim()
            val nis = binding.etNis.text.toString().trim()
            val code = binding.etExamCode.text.toString().trim()

            if (name.isEmpty()) {
                binding.etStudentName.error = "Nama siswa wajib diisi"
                return@setOnClickListener
            }
            if (className.isEmpty()) {
                binding.actClass.error = "Pilih / ketik kelas"
                return@setOnClickListener
            }
            if (code.isEmpty()) {
                binding.etExamCode.error = "Kode / Token Ujian wajib diisi"
                return@setOnClickListener
            }

            val student = StudentInfo(
                studentId = if (nis.isNotEmpty()) nis else "STU-${System.currentTimeMillis().toString().takeLast(6)}",
                name = name,
                className = className,
                nis = nis
            )

            val session = ExamRepository.getExamSessionByCode(code)

            val intent = Intent(this, ExamActivity::class.java).apply {
                putExtra("EXTRA_STUDENT_JSON", Gson().toJson(student))
                putExtra("EXTRA_SESSION_JSON", Gson().toJson(session))
            }
            startActivity(intent)
        }
    }
}
