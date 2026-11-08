package com.example

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide

class MainActivity : AppCompatActivity() {

    companion object {
        private const val EXAM_URL = "https://smayppkasisisentani.sch.id/webujian/landingpagehp.html"
        private const val LOGO_URL = "https://smayppkasisisentani.sch.id/jurnalguru/121212.png"
        private const val ALLOWED_DOMAIN = "smayppkasisisentani.sch.id"
        private const val MAX_VIOLATIONS = 3
    }

    private lateinit var webViewExam: WebView
    private lateinit var splashContainer: LinearLayout
    private lateinit var imgLogo: ImageView
    private lateinit var tvStatus: TextView

    private var violationCounter = 0
    private var isExamStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Blokir Tangkapan Layar, Perekam Layar, dan Recent Apps Preview
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContentView(R.layout.activity_main)

        initViews()
        FirebaseManager.initialize(this)
        loadSchoolLogo()
        applyImmersiveFullScreen()
        setupWebView()
        setupBackNavigation()
        checkFloatingOverlayPermission()
    }

    private fun initViews() {
        webViewExam = findViewById(R.id.webViewExam)
        splashContainer = findViewById(R.id.splashContainer)
        imgLogo = findViewById(R.id.imgLogo)
        tvStatus = findViewById(R.id.tvStatus)

        // Anti-Floating: Mengabaikan input sentuhan jika ada aplikasi melayang yang menutupi layar
        findViewById<View>(R.id.rootView).filterTouchesWhenObscured = true
        webViewExam.filterTouchesWhenObscured = true
    }

    private fun loadSchoolLogo() {
        Glide.with(this)
            .load(LOGO_URL)
            .placeholder(android.R.drawable.ic_menu_gallery)
            .error(android.R.drawable.ic_delete)
            .into(imgLogo)
    }

    // 2. Layar Penuh Imersif (Menyembunyikan Tombol Navigasi & Status Bar)
    private fun applyImmersiveFullScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { insetsController ->
                insetsController.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                insetsController.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }

    // 3. Konfigurasi WebView yang Diperketat
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webSettings: WebSettings = webViewExam.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.databaseEnabled = true
        webSettings.allowFileAccess = false
        webSettings.allowContentAccess = false
        webSettings.setSupportMultipleWindows(false)

        // Matikan context menu copy-paste seleksi teks dari sisi WebView
        webViewExam.setOnLongClickListener { true }
        webViewExam.isHapticFeedbackEnabled = false

        webViewExam.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host
                if (host != null && host.contains(ALLOWED_DOMAIN)) {
                    return false
                }
                Toast.makeText(this@MainActivity, "Akses ke luar domain ujian diblokir!", Toast.LENGTH_SHORT).show()
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                tvStatus.text = "Memuat soal ujian..."
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                splashContainer.visibility = View.GONE
                if (!isExamStarted) {
                    isExamStarted = true
                    FirebaseManager.recordExamSessionStarted(this@MainActivity, url ?: EXAM_URL)
                    startAppPinning()
                }
            }
        }

        webViewExam.loadUrl(EXAM_URL)
    }

    // 4. Deteksi Izin Floating Window (Anti Floating Apps)
    private fun checkFloatingOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                AlertDialog.Builder(this)
                    .setTitle("Keamanan Terdeteksi")
                    .setMessage("Aplikasi mendeteksi izin Floating Window / Tampilan di Atas Aplikasi Lain aktif pada perangkat ini. Harap nonaktifkan sebelum memulai ujian.")
                    .setCancelable(false)
                    .setPositiveButton("Tutup") { _, _ -> finishAffinity() }
                    .show()
            }
        }
    }

    // 5. Mengunci Aplikasi (App Pinning / Kiosk Mode Sederhana)
    private fun startAppPinning() {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (am != null && am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
                    startLockTask()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // 6. Deteksi Kehilangan Fokus Layar (Floating Bubble, Split Screen, Buka Aplikasi Lain)
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveFullScreen()
        } else {
            if (isExamStarted) {
                handleViolation("Layar ujian tertutup jendela melayang atau aplikasi beralih fokus!")
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isExamStarted) {
            handleViolation("Dilarang menekan tombol Home atau berpindah aplikasi!")
        }
    }

    private fun handleViolation(reason: String) {
        violationCounter++
        FirebaseManager.recordViolation(violationCounter, reason)

        if (violationCounter >= MAX_VIOLATIONS) {
            FirebaseManager.recordExamFinished("DISQUALIFIED")
            AlertDialog.Builder(this)
                .setTitle("Diskualifikasi Ujian")
                .setMessage("Anda telah meninggalkan layar ujian sebanyak $violationCounter kali.\nAplikasi akan ditutup secara otomatis.")
                .setCancelable(false)
                .setPositiveButton("Keluar") { _, _ -> finishAffinity() }
                .show()
        } else {
            Toast.makeText(this, "PERINGATAN ($violationCounter/$MAX_VIOLATIONS): $reason", Toast.LENGTH_LONG).show()
        }
    }

    // 7. Cegah Tombol Back Fisik/Virtual Menutup Ujian
    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                showExitDialog()
            }
        })
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            showExitDialog()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showExitDialog() {
        AlertDialog.Builder(this)
            .setTitle("Konfirmasi Keluar")
            .setMessage("Apakah Anda yakin ingin menyelesaikan dan keluar dari ujian?")
            .setPositiveButton("Ya, Keluar") { _, _ ->
                FirebaseManager.recordExamFinished("COMPLETED")
                try {
                    stopLockTask()
                } catch (ignored: Exception) {}
                finishAffinity()
            }
            .setNegativeButton("Lanjutkan Ujian", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        FirebaseManager.cleanup()
    }
}
