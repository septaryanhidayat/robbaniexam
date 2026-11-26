package com.ryan.robbaniexam.security

enum class ViolationType(val title: String, val defaultMessage: String) {
    APP_SWITCHED("Pindah Aplikasi", "Siswa terdeteksi keluar atau memindahkan aplikasi ujian."),
    SCREENSHOT_ATTEMPT("Tangkapan Layar", "Percobaan tangkapan/rekaman layar terdeteksi dan diblokir."),
    SPLIT_SCREEN_DETECTED("Layar Ganda / Split Screen", "Mode split screen / multi-window terdeteksi aktif."),
    DEVELOPER_MODE_ACTIVE("Developer Options / ADB", "USB Debugging atau Opsi Pengembang terdeteksi aktif."),
    FLOATING_OVERLAY_DETECTED("Aplikasi Melayang", "Aplikasi melayang / overlay terdeteksi di atas layar ujian."),
    NETWORK_DISCONNECTED("Koneksi Terputus", "Koneksi internet terputus saat ujian berlangsung.")
}
