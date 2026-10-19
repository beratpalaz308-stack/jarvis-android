package com.jarvis.ev

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private lateinit var reactor: ReactorView
    private lateinit var status: TextView
    private lateinit var heard: TextView
    private lateinit var logView: TextView
    private lateinit var key: EditText
    private lateinit var btn: Button
    private val cyan = Color.parseColor("#00E5FF")
    private val bg = Color.parseColor("#05080F")
    private var logText = ""

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i == null) return
            if (i.hasExtra("heard")) { heard.text = "Duyuyorum: " + i.getStringExtra("heard"); return }
            if (i.hasExtra("rms")) {
                val v = i.getFloatExtra("rms", 0f)
                if (v > reactor.level) reactor.level = v
                return
            }
            update(i.getStringExtra("state") ?: "off", i.getStringExtra("log") ?: "")
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun styled(b: Button, text: String) {
        b.text = text
        b.setTextColor(cyan)
        b.typeface = Typeface.MONOSPACE
        b.background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT); setStroke(dp(2), cyan); cornerRadius = dp(10).toFloat()
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(18), dp(36), dp(18), dp(18))
        }
        val title = TextView(this).apply {
            text = "J.A.R.V.I.S v5"; setTextColor(cyan); textSize = 26f
            typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER; letterSpacing = 0.3f
        }
        reactor = ReactorView(this)
        status = TextView(this).apply {
            setTextColor(cyan); textSize = 13f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
        }
        heard = TextView(this).apply {
            setTextColor(Color.parseColor("#7FA9B3")); textSize = 12f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
        }
        logView = TextView(this).apply {
            setTextColor(Color.parseColor("#9FDDE6")); textSize = 13f; typeface = Typeface.MONOSPACE
        }
        val scroll = ScrollView(this).apply { addView(logView) }
        key = EditText(this).apply {
            hint = "Gemini API anahtarı"; setHintTextColor(Color.parseColor("#3E6870"))
            setTextColor(Color.WHITE); typeface = Typeface.MONOSPACE; textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(getSharedPreferences("jarvis", 0).getString("api", ""))
        }
        btn = Button(this)
        styled(btn, "BAŞLAT")
        btn.setOnClickListener { toggle() }

        fun mk(label: String, act: () -> Unit): Button {
            val b = Button(this); styled(b, label); b.textSize = 11f; b.setOnClickListener { act() }; return b
        }
        val bAcc = mk("ERİŞİLEBİLİRLİK") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        val bNot = mk("BİLDİRİM İZNİ") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        val bOv = mk("EKRAN ÜSTÜ") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        val bBat = mk("PİL İZNİ") { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        val bWr = mk("AYAR YAZMA") {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        }
        fun rowOf(a: Button, b: Button?): LinearLayout {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            r.addView(a, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(0, dp(3), dp(4), dp(3)) })
            if (b != null) r.addView(b, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(4), dp(3), 0, dp(3)) })
            else r.addView(View(this), LinearLayout.LayoutParams(0, dp(44), 1f))
            return r
        }

        root.addView(title)
        root.addView(reactor, LinearLayout.LayoutParams(-1, 0, 1.2f))
        root.addView(status, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(6), 0, dp(8)) })
        root.addView(heard, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(6)) })
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 0.8f))
        root.addView(key, LinearLayout.LayoutParams(-1, -2))
        root.addView(btn, LinearLayout.LayoutParams(-1, dp(52)).apply { setMargins(0, dp(8), 0, dp(8)) })
        root.addView(rowOf(bAcc, bNot), LinearLayout.LayoutParams(-1, -2))
        root.addView(rowOf(bOv, bBat), LinearLayout.LayoutParams(-1, -2))
        root.addView(rowOf(bWr, null), LinearLayout.LayoutParams(-1, -2))
        setContentView(root)

        askPerms()
    }

    private fun askPerms() {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG,
            Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR, Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(list.toTypedArray(), 1)
    }

    private fun toggle() {
        getSharedPreferences("jarvis", 0).edit().putString("api", key.text.toString().trim()).apply()
        if (JarvisService.running) {
            stopService(Intent(this, JarvisService::class.java))
            update("off", "")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askPerms(); return
        }
        if (key.text.isBlank()) {
            Toast.makeText(this, "Önce Gemini API anahtarını yazın efendim", Toast.LENGTH_LONG).show(); return
        }
        startForegroundService(Intent(this, JarvisService::class.java))
        update("wake", "")
    }

    private fun update(state: String, log: String) {
        reactor.mode = when (state) { "command" -> 1; "think" -> 2; "speak" -> 3; else -> 0 }
        status.text = when (state) {
            "wake" -> "DİNLİYORUM · JARVIS, KAREN, ÇAVUŞ, CUMALİ, HEY"
            "command" -> "BUYRUN EFENDİM..."
            "think" -> "ARAŞTIRIYORUM..."
            "speak" -> "KONUŞUYOR"
            else -> "ÇEVRİMDIŞI"
        }
        if (log.isNotBlank()) {
            logText = (logText + "\n" + log).takeLast(900)
            logView.text = logText.trim()
        }
        btn.text = if (state == "off") "BAŞLAT" else "KAPAT"
    }

    override fun onResume() {
        super.onResume()
        val f = IntentFilter(JarvisService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(rx, f)
        update(if (JarvisService.running) "wake" else "off", "")
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(rx)
    }
}
