package com.ryan.robbaniexam.ui.scan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.ryan.robbaniexam.databinding.ActivityQrScanBinding

class QrScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQrScanBinding

    private val zxingLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val intent = Intent().apply {
                putExtra("EXTRA_QR_RESULT", result.contents)
            }
            setResult(RESULT_OK, intent)
            finish()
        } else {
            Toast.makeText(this, "Pemindaian Dibatalkan", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            launchScanner()
        } else {
            Toast.makeText(this, "Izin kamera diperlukan untuk memindai QR Code", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchScanner()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Arahkan kamera ke QR Code Ujian di Dashboard Guru")
            setCameraId(0)
            setBeepEnabled(true)
            setBarcodeImageEnabled(false)
            setOrientationLocked(true)
        }
        zxingLauncher.launch(options)
    }
}
