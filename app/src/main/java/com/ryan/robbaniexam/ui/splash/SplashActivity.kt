package com.ryan.robbaniexam.ui.splash

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ryan.robbaniexam.databinding.ActivitySplashBinding
import com.ryan.robbaniexam.security.ExamSecurityManager
import com.ryan.robbaniexam.ui.main.MainActivity

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val securityManager = ExamSecurityManager(this)
        val hasDevOptions = securityManager.checkDeveloperOptions(this)

        if (hasDevOptions) {
            binding.tvStatus.text = "Peringatan: Opsi Pengembang / USB Debugging Aktif!"
            binding.tvStatus.setTextColor(getColor(android.R.color.holo_red_light))
        } else {
            binding.tvStatus.text = "Sistem Keamanan Ujian Siap..."
        }

        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 1500)
    }
}
