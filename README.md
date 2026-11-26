# Robbani Exam v2.0

Sistem Ujian Berbasis Android Anti-Curang & Dashboard Pengawas Real-Time untuk **Sekolah Islam Terpadu ROBBANI**.

---

## Fitur Utama

### 1. Aplikasi Android Siswa (`app/`)
- **Integrasi Moodle LMS & QR Code:** Siswa dapat memindai QR Code dari guru atau mengetik kode ujian singkat (otomatis di-resolve dari Firebase `/exams/{kode}`) maupun URL langsung.
- **Keamanan Ujian & Anti-Curang:**
  - Blokir tangkapan layar & perekaman layar (`FLAG_SECURE`).
  - Mode Imersif & *Screen Pinning* (`startLockTask()`), lengkap dengan **Watchdog 500 ms** untuk mendeteksi kombinasi tombol **Recent + Back** dan menarik aplikasi kembali ke layar depan (didukung izin *Tampilkan di atas aplikasi lain* / `SYSTEM_ALERT_WINDOW`).
  - Deteksi pindah aplikasi, split-screen, dan Opsi Pengembang / USB Debugging.
  - Cooldown anti-duplikasi pelanggaran & proteksi agar dialog PIN Pengawas tidak memicu pelanggaran palsu.
  - Blokir copy-paste & long-press pada `WebView` ujian (dengan dukungan cookie login Moodle).
- **Alarm Sirine di HP Siswa:**
  - Bunyi peringatan + getar saat pelanggaran terjadi, dan **sirine darurat ~4,5 detik** pada stream `USAGE_ALARM` saat batas pelanggaran terlampaui (`TERKUNCI`).
- **Sinkronisasi Real-Time (Firebase SSE Stream + Fallback):**
  - Mengirim status pengerjaan, sisa waktu, dan riwayat pelanggaran ke Firebase Realtime Database.
  - Menerima perintah **Buka Kunci Jarak Jauh (`UNLOCK`)**, **Pesan Pengawas (`MESSAGE` modal lebar di tengah layar)**, dan **Akhiri Ujian (`FORCE_FINISH`)** secara instan.
  - Menampilkan **Pengumuman Beranda** dari pengawas dan daftar **Kelas Dinamis** dari Firebase.

### 2. Dashboard Admin & Guru (`admin-dashboard/`)
- **Login & Hak Akses Berbasis Kelas (Multi-Guru):**
  - **Super Admin:** Akses penuh seluruh kelas, mengelola **Data Kelas Sekolah (`/classes`)**, mengelola **Akun Guru & Hak Akses Kelas (`/teachers`)**, mengatur pengumuman global, serta mengganti password Admin & konfigurasi Firebase.
  - **Guru:** Hanya melihat dan mengelola sesi ujian, peserta live, alarm pelanggaran, filter kelas, serta ekspor CSV untuk **kelas yang diberikan kepadanya** (terisolasi antar guru).
- **Manajemen Sesi Ujian & Kartu QR:** Buat/edit sesi ujian Moodle, cetak kartu QR, dan sinkronisasi kode ujian ke Firebase.
- **Live Proctoring & Notifikasi SweetAlert2:** Pemantauan real-time, alarm sirine 3,6 detik, buka kunci jarak jauh, kirim notifikasi pesan ke salah satu siswa, riwayat log pelanggaran, akhiri ujian paksa, dan ekspor CSV UTF-8.

---

## Struktur Proyek

```text
RobbaniExam2/
├── app/                          # Source code aplikasi Android (Kotlin + Material Components)
├── admin-dashboard/              # Web Dashboard Admin & Guru (HTML, CSS, JS + Firebase Compat SDK)
│   ├── index.html
│   ├── css/style.css
│   └── js/app.js
├── releases/
│   └── RobbaniExam-v2.0.apk      # APK Release siap pasang (signed)
├── buka-dashboard.bat            # Shortcut menjalankan Dashboard Admin lokal di port 8085
└── README.md
```

---

## Struktur Data Firebase Realtime Database

URL Database: `https://robbaniexam-default-rtdb.firebaseio.com`

- `/classes/{classId}` — Daftar kelas sekolah yang dikelola Admin (dibaca oleh Dashboard & dropdown kelas aplikasi siswa).
- `/teachers/{teacherId}` — Akun guru (`name`, `username`, `password`, `assignedClasses`).
- `/exams/{examCode}` — Konfigurasi ujian Moodle (`title`, `subject`, `moodleUrl`, `durationMinutes`, `maxViolations`, `adminPin`, `targetClasses`).
- `/students/{studentId}` — Status live peserta, `remainingSeconds`, `violationLogs`, dan perintah jarak jauh (`remoteCommand`, `remoteMessage`).
- `/settings/announcement` — Pengumuman global yang tampil di beranda aplikasi siswa.
- `/settings/adminAuth` — Password kustom untuk Super Admin.

---

## Cara Menjalankan & Build

### Menjalankan Dashboard Admin Lokal
1. Jalankan [buka-dashboard.bat](buka-dashboard.bat) (atau jalankan `python -m http.server 8085 --directory admin-dashboard`), lalu buka `http://localhost:8085`.
2. Login awal Super Admin:
   - **Username:** `admin`
   - **Password default:** `robbani2026` *(disarankan segera diganti lewat menu **Server** setelah login).*
3. Untuk produksi online, seluruh isi folder `admin-dashboard/` dapat langsung diunggah ke Firebase Hosting, Vercel, Netlify, atau cPanel.

### Build APK & Menjalankan Unit Test (CLI Windows)
Gunakan JDK modern (misalnya JBR bawaan Android Studio):

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat testDebugUnitTest assembleDebug assembleRelease --no-daemon
```

> **Catatan keamanan:** Jangan meng-commit file keystore (`*.jks`) maupun `local.properties` ke repository publik.

