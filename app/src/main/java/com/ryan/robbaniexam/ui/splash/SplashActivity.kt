package com.ryan.robbaniexam.ui.splash

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ryan.robbaniexam.R
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
            binding.tvStatus.text = "Peringatan: Opsi Pengembang / USB Debugging aktif!"
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.robbani_yellow))
        } else {
            binding.tvStatus.text = "Sistem keamanan ujian siap..."
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.robbani_green))
        }

        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing && !isDestroyed) {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }, 1200)
    }
}
