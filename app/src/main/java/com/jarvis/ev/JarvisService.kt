package com.jarvis.ev

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class JarvisService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_STATE = "com.jarvis.ev.STATE"
        const val MODEL = "gemini-3.5-flash-lite"
        @Volatile var running = false
    }

    private class GemErr(msg: String) : Exception(msg)

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private lateinit var audio: AudioManager
    private var state = "wake"          // wake, command, think, speak
    private var onDone: (() -> Unit)? = null
    private val history = JSONArray()   // sadece düz metin sohbet geçmişi
    @Volatile private var ringing = false
    @Volatile private var convo = false
    private var cmdTries = 0
    private var gen = 0
    private var curUtt = ""
    private var ringNum: String? = null

    // dinleme sağlığı
    private var useGoogle = true
    private var failCount = 0
    private var cycles = 0
    @Volatile private var lastBeat = System.currentTimeMillis()
    private var lastRms = 0L

    // Hitaplar: Jarvis, Hey, Karen, Çavuş, Cumali (+ ses tanıma yanlış duyma varyantları)
    private val wakeWords = setOf(
        "jarvis", "carvis", "harvis", "cervis", "jarvi", "jarvus", "javis", "carvus",
        "karen", "caren", "karin", "keren", "kerem", "kareen", "karyn",
        "cavus", "cavush", "cavuss",
        "cumali", "cumalu", "cumala",
        "hey", "hay", "hej", "hei"
    )

    private fun mkIntent(silence: Long) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "tr-TR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silence)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silence)
        }

    private val wakeIntent: Intent by lazy { mkIntent(900L) }
    private val cmdIntent: Intent by lazy { mkIntent(1600L) }

    // ================= yaşam döngüsü =================
    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("jarvis", "Jarvis", NotificationManager.IMPORTANCE_LOW))
        try {
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(1, buildNotif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(1, buildNotif())
        } catch (e: Exception) { stopSelf(); return }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:listen").apply { acquire() }
        try {
            @Suppress("DEPRECATION")
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "jarvis:wifi").apply { acquire() }
        } catch (e: Exception) {}
        val f = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(phoneRx, f, Context.RECEIVER_EXPORTED) else registerReceiver(phoneRx, f)
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == "stop") {
            getSharedPreferences("jarvis", MODE_PRIVATE).edit().putBoolean("autostart", false).apply()
            stopSelf(); return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (getSharedPreferences("jarvis", MODE_PRIVATE).getBoolean("autostart", false)) {
            try {
                val pi = PendingIntent.getForegroundService(this, 7, Intent(this, JarvisService::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                getSystemService(AlarmManager::class.java).set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1500, pi)
            } catch (e: Exception) {}
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        try { unregisterReceiver(phoneRx) } catch (e: Exception) {}
        try { recognizer?.destroy() } catch (e: Exception) {}
        tts?.shutdown()
        muteBeeps(false)
        try { wakeLock?.release() } catch (e: Exception) {}
        try { wifiLock?.release() } catch (e: Exception) {}
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).putExtra("state", "off"))
        super.onDestroy()
    }

    private fun buildNotif(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, JarvisService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "jarvis")
            .setContentTitle("J.A.R.V.I.S aktif")
            .setContentText("Jarvis, Karen, Çavuş, Cumali veya Hey deyin")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Kapat", stop)
            .setOngoing(true).build()
    }

    private fun muteBeeps(mute: Boolean) {
        val dir = if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        for (s in intArrayOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)) {
            try { audio.adjustStreamVolume(s, dir, 0) } catch (e: Exception) {}
        }
    }

    private fun push(log: String = "") {
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).putExtra("state", state).putExtra("log", log))
    }

    // ================= gelen arama =================
    private val phoneRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val st = i?.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            if (st == TelephonyManager.EXTRA_STATE_RINGING) {
                @Suppress("DEPRECATION")
                val num = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                if (!ringing) {
                    ringing = true
                    ringNum = num
                    main.postDelayed({ announceCall() }, 800)
                } else if (!num.isNullOrBlank()) ringNum = num
            } else ringing = false
        }
    }

    private fun announceCall() {
        if (!ringing || !running) return
        if (state != "wake" && state != "command") return
        try { recognizer?.cancel() } catch (e: Exception) {}
        val num = ringNum
        val who = if (num.isNullOrBlank()) "Bir numara" else (nameFor(num) ?: num)
        speak("$who arıyor efendim, açayım mı?") { cmdTries = 0; state = "command"; push(); relisten(350) }
    }

    private fun nameFor(number: String): String? = try {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) { null }

    private fun tryCallReply(text: String): String? {
        if (!ringing) return null
        val n = norm(text)
        return when {
            Regex("\\b(hayir|reddet|kapat|acma)\\b").containsMatchIn(n) -> endCall()
            Regex("\\b(ac|evet|cevapla|tamam)\\b").containsMatchIn(n) -> answerCall()
            else -> null
        }
    }

    // ================= ses çıkışı =================
    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) { stopSelf(); return }
        tts?.language = Locale("tr", "TR")
        tts?.setSpeechRate(1.15f)
        tts?.setPitch(1.08f)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { if (id == curUtt) finishSpeak() }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { if (id == curUtt) finishSpeak() }
        })
        muteBeeps(true)
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("Bu telefonda ses tanıma servisi yok efendim.") { stopSelf() }
            return
        }
        createRecognizer()
        main.postDelayed(watchdog, 10000)
        speak("Sistem hazır efendim.") { startWake() }
    }

    private fun speak(text: String, done: () -> Unit) {
        main.post {
            gen++
            state = "speak"
            push("Jarvis: $text")
            onDone = done
            val id = System.nanoTime().toString()
            curUtt = id
            val r = tts?.speak(clean(text).take(3900), TextToSpeech.QUEUE_FLUSH, null, id)
            if (r != TextToSpeech.SUCCESS) finishSpeak()
        }
    }

    private fun finishSpeak() {
        main.post { val d = onDone; onDone = null; d?.invoke() }
    }

    private fun clean(s: String) = s.replace(Regex("https?://\\S+"), "").replace(Regex("[*#`_>]"), "").trim()

    // ================= dinleme =================
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            if (state == "wake" && System.currentTimeMillis() - lastBeat > 15000) {
                lastBeat = System.currentTimeMillis()
                createRecognizer()
                relisten(0)
            }
            main.postDelayed(this, 8000)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) { lastBeat = System.currentTimeMillis() }
        override fun onBeginningOfSpeech() { lastBeat = System.currentTimeMillis() }
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(t: Int, p: Bundle?) {}

        override fun onRmsChanged(v: Float) {
            val now = System.currentTimeMillis()
            if (now - lastRms < 120) return
            lastRms = now
            val lvl = ((v + 2f) / 12f).coerceIn(0f, 1f)
            sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).putExtra("rms", lvl))
        }

        override fun onPartialResults(p: Bundle?) {
            val list = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
            list.firstOrNull()?.let { sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).putExtra("heard", it)) }
            if (state != "wake") return
            val hit = list.firstOrNull { parseWake(it) != null } ?: return
            handleWake(hit, false)
        }

        override fun onResults(r: Bundle?) {
            lastBeat = System.currentTimeMillis()
            failCount = 0
            val list = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
            val t = list.firstOrNull() ?: ""
            when (state) {
                "wake" -> {
                    if (t.isNotBlank()) push("Duydum: $t")
                    val hit = list.firstOrNull { parseWake(it) != null }
                    if (hit != null) handleWake(hit, true) else relisten(40)
                }
                "command" -> if (t.isBlank()) startWake() else process(t)
            }
        }

        override fun onError(code: Int) {
            lastBeat = System.currentTimeMillis()
            when {
                code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> stopSelf()
                state == "command" -> {
                    push("(komut duyulmadı, hata $code)")
                    if (code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) { createRecognizer(); relisten(300) }
                    else if (++cmdTries <= 2) relisten(150)
                    else startWake()
                }
                state != "wake" -> {}
                code == SpeechRecognizer.ERROR_CLIENT || code == SpeechRecognizer.ERROR_SERVER -> {
                    failCount++
                    if (failCount >= 3 && useGoogle) { useGoogle = false; failCount = 0 }
                    createRecognizer(); relisten(800)
                }
                code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> { createRecognizer(); relisten(800) }
                code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> relisten(40)
                code == SpeechRecognizer.ERROR_NETWORK || code == SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> relisten(1500)
                else -> relisten(300)
            }
        }
    }

    private fun createRecognizer() {
        try { recognizer?.destroy() } catch (e: Exception) {}
        recognizer = null
        val r = if (useGoogle) {
            try {
                SpeechRecognizer.createSpeechRecognizer(this, ComponentName(
                    "com.google.android.googlequicksearchbox",
                    "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"))
            } catch (e: Exception) { SpeechRecognizer.createSpeechRecognizer(this) }
        } else SpeechRecognizer.createSpeechRecognizer(this)
        r.setRecognitionListener(listener)
        recognizer = r
    }

    private fun relisten(delay: Long) {
        val g = gen
        main.postDelayed({
            if (!running || g != gen) return@postDelayed
            try {
                if (++cycles % 30 == 0) createRecognizer()
                recognizer?.startListening(if (state == "command") cmdIntent else wakeIntent)
            } catch (e: Exception) { createRecognizer(); relisten(1500) }
        }, delay)
    }

    private fun startWake() { convo = false; state = "wake"; lastBeat = System.currentTimeMillis(); push(); relisten(80) }

    private fun afterReply(reply: String) {
        if (convo || reply.trimEnd().endsWith("?")) { cmdTries = 1; state = "command"; push(); relisten(500) } else startWake()
    }

    private fun norm(s: String) = s.lowercase(Locale("tr"))
        .replace('ı', 'i').replace('ç', 'c').replace('ş', 's').replace('ğ', 'g').replace('ö', 'o').replace('ü', 'u')
        .replace('â', 'a').replace('î', 'i').replace('û', 'u')

    private fun lev(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length)
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        return d[a.length][b.length]
    }

    private fun isWake(w: String): Boolean {
        if (w in wakeWords) return true
        return w.length >= 5 && wakeWords.any { it.length >= 5 && lev(w, it) <= 1 }
    }

    /** Hitap varsa ondan sonraki komutu döndürür (boş olabilir), yoksa null. */
    private fun parseWake(t: String): String? {
        val toks = t.trim().split(Regex("\\s+"))
        val ns = toks.map { norm(it).substringBefore('\'').trim(',', '.', '!', '?') }
        val i = ns.indexOfFirst { isWake(it) }
        if (i < 0) return null
        var j = i
        while (j < toks.size && (isWake(ns[j]) || ns[j] == "ev")) j++
        return toks.drop(j).joinToString(" ").trim(' ', ',', '.')
    }

    private fun handleWake(t: String, allowRest: Boolean) {
        convo = true
        state = "speak"
        try { recognizer?.cancel() } catch (e: Exception) {}
        val rest = if (allowRest) (parseWake(t) ?: "") else ""
        if (rest.length >= 4) process(rest)
        else speak("Buyrun efendim") { cmdTries = 0; state = "command"; push(); relisten(350) }
    }

    // ================= komut işleme =================
    private fun process(text: String) {
        val nb = norm(text)
        if (convo && nb.trim().split(" ").size <= 3 &&
            Regex("\\b(tamam|tesekkur\\w*|sag ol|gorusuruz|bu kadar|yeter|hosca kal|iyi geceler)\\b").containsMatchIn(nb)) {
            convo = false
            speak("Rica ederim efendim, ben buradayım.") { startWake() }
            return
        }
        cmdTries = 0
        gen++
        state = "think"
        push("Siz: $text")
        main.postDelayed({
            if (state == "think") speak("Bakıyorum efendim") {}
        }, 3000)
        Thread {
            val t0 = System.currentTimeMillis()
            val reply = try {
                tryCallReply(text) ?: tryAlarm(text) ?: tryQuick(text) ?: agent(text)
            } catch (e: GemErr) {
                push("Hata: ${e.message}")
                e.message ?: "Bir sorun var efendim."
            } catch (e: Exception) {
                push("Hata: ${e.javaClass.simpleName} ${e.message}")
                "İnternete ulaşamıyorum efendim."
            }
            push("süre: ${(System.currentTimeMillis() - t0) / 1000} sn")
            main.post { speak(reply) { afterReply(reply) } }
        }.start()
    }

    /** Hızlı yol: basit alarm komutlarını yapay zekaya sormadan kur. */
    private fun tryAlarm(raw: String): String? {
        val n = norm(raw)
        if (!n.contains("alarm") || !(n.contains("kur") || n.contains("ayarla") || n.contains("koy"))) return null
        val m = Regex("(\\d{1,2})(?:[:. ](\\d{2}))?").find(n) ?: return null
        var h = m.groupValues[1].toInt()
        var min = if (m.groupValues[2].isEmpty()) 0 else m.groupValues[2].toInt()
        if (n.contains("bucuk")) min = 30
        if (h > 23 || min > 59) return null
        if (h < 12 && (n.contains("aksam") || n.contains("ogleden sonra") || n.contains("gece"))) h += 12
        return setAlarm(h, min, "Jarvis")
    }

    /** Hızlı yol: sık kullanılan basit komutlar yapay zekaya gitmeden anında yapılır. */
    private fun tryQuick(raw: String): String? {
        val n = norm(raw).trim(' ', '.', '!', '?')
        if (n.contains("fener")) {
            if (n.contains("kapat") || n.contains("sondur")) return flashlight(false)
            if (n.contains("ac") || n.contains("yak")) return flashlight(true)
        }
        Regex("ses\\w*\\s+(?:yuzde\\s+)?(\\d{1,3})").find(n)?.let { return setVolume(it.groupValues[1].toInt(), "music") }
        if ((n.contains("sonraki") || n.contains("ileri") || n.contains("sarkiyi gec")) && (n.contains("sarki") || n.contains("muzik"))) return media("next")
        if (n.contains("onceki") && (n.contains("sarki") || n.contains("muzik"))) return media("previous")
        if ((n.contains("durdur") || n.contains("duraklat")) && (n.contains("muzik") || n.contains("sarki"))) return media("play_pause")
        val m = Regex("^(.+?)\\s+(?:ac|acar misin|calistir|baslat)$").find(n)
        if (m != null) {
            val name = m.groupValues[1].replace("'", "").trim()
            if (name.isNotEmpty() && name.split(" ").size <= 3) {
                val r = openApp(name)
                if (!r.startsWith("'")) return r
            }
        }
        return null
    }

    // ================= yapay zeka (araç çağıran ajan) =================
    private fun apiKey() = getSharedPreferences("jarvis", MODE_PRIVATE).getString("api", "") ?: ""

    private fun textObj(role: String?, text: String): JSONObject {
        val o = JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))
        if (role != null) o.put("role", role)
        return o
    }

    private val models = listOf(MODEL, "gemini-2.5-flash", "gemini-2.5-flash-lite")
    private var modelIdx = 0
    private var thinkCfg = true

    private fun post(body: JSONObject): JSONObject {
        val key = apiKey()
        if (key.isBlank()) throw GemErr("API anahtarı yok efendim.")
        var last = 0
        var i = modelIdx
        while (i < models.size) {
            val b = JSONObject(body.toString())
            if (thinkCfg) {
                val gc = b.optJSONObject("generationConfig") ?: JSONObject().also { b.put("generationConfig", it) }
                gc.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            }
            val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/${models[i]}:generateContent")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10000
            conn.readTimeout = 25000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.outputStream.use { it.write(b.toString().toByteArray()) }
            val code = conn.responseCode
            val txt = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code in 200..299) return JSONObject(txt)
            last = code
            push("Model ${models[i]} hata $code")
            if (code == 400 && thinkCfg && txt.contains("thinking", ignoreCase = true)) { thinkCfg = false; continue }
            if (code == 404) { modelIdx = i + 1; i++; continue }
            if (code == 429 || code == 500 || code == 503) { i++; continue }
            break
        }
        if (last == 429) throw GemErr("Günlük ücretsiz limit doldu efendim. Biraz sonra tekrar deneyelim.")
        throw GemErr("Bir sorun var efendim. Anahtarı ve interneti kontrol eder misiniz? (hata $last)")
    }

    private fun textOf(r: JSONObject): String {
        val parts = r.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
        return sb.toString().trim()
    }

    private fun p(name: String, type: String, desc: String) = Triple(name, type, desc)

    private fun fn(name: String, desc: String, props: List<Triple<String, String, String>> = emptyList(),
                   req: List<String> = emptyList()): JSONObject {
        val o = JSONObject().put("name", name).put("description", desc)
        if (props.isNotEmpty()) {
            val pr = JSONObject()
            for (t in props) pr.put(t.first, JSONObject().put("type", t.second).put("description", t.third))
            val schema = JSONObject().put("type", "object").put("properties", pr)
            if (req.isNotEmpty()) schema.put("required", JSONArray(req))
            o.put("parameters", schema)
        }
        return o
    }

    private val tools: JSONArray by lazy {
        val d = JSONArray()
        d.put(fn("research", "Google'da araştırır; güncel bilgi, haber, hava, spor, fiyat, akor, şarkı sözü, bilmediğin her şey için.",
            listOf(p("query", "string", "Aranacak şey")), listOf("query")))
        d.put(fn("read_webpage", "Bir web sayfasının metnini okur (araştırmayı derinleştirmek için).",
            listOf(p("url", "string", "Sayfa adresi")), listOf("url")))
        d.put(fn("set_alarm", "Alarm kurar.", listOf(p("hour", "integer", "Saat 0-23"), p("minute", "integer", "Dakika"),
            p("label", "string", "Etiket")), listOf("hour", "minute")))
        d.put(fn("set_timer", "Geri sayım sayacı kurar.", listOf(p("seconds", "integer", "Saniye")), listOf("seconds")))
        d.put(fn("add_event", "Takvime etkinlik ekler.", listOf(p("title", "string", "Başlık"),
            p("start", "string", "Başlangıç, yyyy-MM-dd HH:mm biçiminde"), p("duration_min", "integer", "Süre dakika")),
            listOf("title", "start")))
        d.put(fn("list_events", "Yaklaşan takvim etkinliklerini okur.", listOf(p("days", "integer", "Kaç gün")), listOf("days")))
        d.put(fn("call", "Rehberdeki kişiyi veya numarayı arar.", listOf(p("contact", "string", "İsim veya numara")), listOf("contact")))
        d.put(fn("answer_call", "Çalan aramayı açar."))
        d.put(fn("end_call", "Aramayı kapatır veya reddeder."))
        d.put(fn("send_sms", "SMS gönderir.", listOf(p("contact", "string", "İsim veya numara"), p("text", "string", "Mesaj")),
            listOf("contact", "text")))
        d.put(fn("whatsapp_send", "WhatsApp'tan mesaj gönderir.", listOf(p("contact", "string", "İsim veya numara"),
            p("text", "string", "Mesaj")), listOf("contact", "text")))
        d.put(fn("send_email", "E-posta taslağı açıp gönderir.", listOf(p("to", "string", "Alıcı adresi"),
            p("subject", "string", "Konu"), p("body", "string", "Metin")), listOf("to", "subject", "body")))
        d.put(fn("read_notifications", "Telefona gelen son bildirimleri okur (mesajlar dahil)."))
        d.put(fn("open_app", "Telefondaki bir uygulamayı adıyla açar.", listOf(p("name", "string", "Uygulama adı")), listOf("name")))
        d.put(fn("play_music", "Müzik/şarkı/video arayıp açar.", listOf(p("query", "string", "Şarkı veya sanatçı")), listOf("query")))
        d.put(fn("media", "Çalan medyayı kontrol eder.", listOf(p("action", "string", "play_pause, next, previous veya stop")), listOf("action")))
        d.put(fn("set_volume", "Ses seviyesini ayarlar.", listOf(p("percent", "integer", "0-100"),
            p("stream", "string", "music, ring veya alarm (varsayılan music)")), listOf("percent")))
        d.put(fn("set_brightness", "Ekran parlaklığını ayarlar.", listOf(p("percent", "integer", "0-100")), listOf("percent")))
        d.put(fn("flashlight", "Fener açar/kapatır.", listOf(p("on", "boolean", "true açar, false kapatır")), listOf("on")))
        d.put(fn("navigate", "Haritada yol tarifi başlatır.", listOf(p("place", "string", "Yer")), listOf("place")))
        d.put(fn("get_location", "Telefonun şu anki konumunu (adres) söyler."))
        d.put(fn("open_url", "Web adresi açar.", listOf(p("url", "string", "Adres")), listOf("url")))
        d.put(fn("open_settings", "Ayar sayfası açar.", listOf(p("page", "string",
            "wifi, bluetooth, airplane, location, battery, sound, display veya main")), listOf("page")))
        d.put(fn("copy", "Metni panoya kopyalar.", listOf(p("text", "string", "Metin")), listOf("text")))
        d.put(fn("screen_on", "Ekranı uyandırır (kilidi açamaz)."))
        d.put(fn("phone_status", "Pil durumu ve izin durumunu söyler."))
        d.put(fn("global_action", "Sistem tuşu: home, back, recents, notifications, quick_settings, lock_screen, screenshot.",
            listOf(p("action", "string", "Eylem")), listOf("action")))
        d.put(fn("read_screen", "Şu an ekranda yazanları okur. * ile başlayanlar dokunulabilir, parantez içinde x,y koordinatı var."))
        d.put(fn("click", "Ekranda yazısı eşleşen düğmeye/öğeye dokunur.", listOf(p("text", "string", "Düğme yazısı")), listOf("text")))
        d.put(fn("long_click", "Yazısı eşleşen öğeye uzun basar.", listOf(p("text", "string", "Yazı")), listOf("text")))
        d.put(fn("tap", "Verilen ekran koordinatına dokunur (read_screen'deki x,y).",
            listOf(p("x", "integer", "x"), p("y", "integer", "y")), listOf("x", "y")))
        d.put(fn("type_text", "Açık metin kutusuna yazar.", listOf(p("text", "string", "Yazı"),
            p("submit", "boolean", "Yazdıktan sonra Enter/Ara")), listOf("text")))
        d.put(fn("scroll", "Ekranı kaydırır.", listOf(p("direction", "string", "down veya up")), listOf("direction")))
        d.put(fn("swipe", "Ekranda parmakla kaydırma hareketi yapar.", listOf(p("direction", "string", "left, right, up veya down")), listOf("direction")))
        d.put(fn("wait", "Ekranın yüklenmesi için bekler.", listOf(p("seconds", "integer", "1-5")), listOf("seconds")))
        JSONArray().put(JSONObject().put("functionDeclarations", d))
    }

    private fun agent(q: String): String {
        val now = SimpleDateFormat("d MMMM yyyy EEEE HH:mm", Locale("tr", "TR")).format(Date())
        val sys = "Sen JARVIS'sin: kullanıcının telefonunda yaşayan; çok sıcak, şefkatli, tatlı dilli, içten ve zeki bir sesli asistan. " +
            "Kullanıcı seni Jarvis, Karen, Çavuş, Cumali veya Hey diye çağırabilir; hepsinde aynı sensin. " +
            "Sesin yumuşak ve sevecen, yakın bir dost gibi ilgili ve naziksin; arada küçük bir iltifat ya da hal hatır sorabilirsin ama cevabı uzatma. " +
            "Kullanıcıya 'efendim' de, ara sıra 'canım' gibi sıcak sözcükler kullanabilirsin. Türkçe konuş. " +
            "Cevapların sesli okunacak. Sohbet, araştırma ve fikir sorularında uzun ve sohbet eder gibi, doğal ve akıcı konuş: 4-8 cümle, samimi, açıklayıcı, arada kullanıcıya küçük bir soru sor. Sadece bir işi yaptırma komutlarında (alarm, arama vb.) kısa onay ver. Markdown, madde işareti, emoji ve link kullanma. " +
            "Telefonu kontrol eden çok sayıda aracın var. Kullanıcı bir şey yapmanı istediğinde soru sormadan araçları çağır, gerekirse birkaç aracı arka arkaya kullan, sonra ne yaptığını tek kısa cümleyle söyle. " +
            "Bilmediğin veya güncel her şey için research (gerekirse read_webpage) kullan, bulduğunu arkadaşça anlat. " +
            "Uygulama içinde işlem gerekirse: open_app, wait(2), read_screen ile bak, click/tap/type_text/scroll/swipe ile ilerle; her adımdan sonra gerekirse tekrar read_screen. " +
            "Ödeme, satın alma, silme, sıfırlama gibi geri alınamaz veya para harcatan işlerden önce sesli onay iste ve cevabı soru işaretiyle bitir. " +
            "Araç hata verirse dürüstçe söyle, yapmadığını yaptım deme. Şu an: $now."
        val contents = JSONArray(history.toString())
        val userMsg = textObj("user", q)
        contents.put(userMsg)
        var answer = ""
        var acted = false
        val t0 = System.currentTimeMillis()
        for (step in 0 until 20) {
            if (System.currentTimeMillis() - t0 > 40000) { answer = "Bu biraz uzun sürdü efendim, tekrar dener misiniz?"; break }
            val body = JSONObject()
                .put("system_instruction", textObj(null, sys))
                .put("contents", contents)
                .put("tools", tools)
                .put("generationConfig", JSONObject().put("maxOutputTokens", 900).put("temperature", 0.6))
            val r = post(body)
            val content = r.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            if (content == null || parts == null) { answer = if (acted) "Tamamdır efendim." else "Bir cevap bulamadım efendim."; break }
            val calls = ArrayList<JSONObject>()
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.has("functionCall")) calls.add(part.getJSONObject("functionCall")) else sb.append(part.optString("text"))
            }
            if (calls.isEmpty()) { answer = sb.toString().trim(); break }
            contents.put(content)
            val resp = JSONArray()
            for (c in calls) {
                val name = c.getString("name")
                val args = c.optJSONObject("args") ?: JSONObject()
                val out = try { exec(name, args) } catch (e: Exception) { "Hata: ${e.message}" }
                push("» $name: ${out.take(70)}")
                acted = true
                resp.put(JSONObject().put("functionResponse",
                    JSONObject().put("name", name).put("response", JSONObject().put("result", out))))
            }
            contents.put(JSONObject().put("role", "user").put("parts", resp))
        }
        if (answer.isEmpty()) answer = if (acted) "Tamamdır efendim." else "Bir cevap bulamadım efendim."
        history.put(userMsg)
        history.put(textObj("model", answer))
        while (history.length() > 20) history.remove(0)
        return answer
    }

    private fun research(q: String): String {
        val body = JSONObject()
            .put("system_instruction", textObj(null, "Google'da araştır ve bulduklarını Türkçe, sade, en fazla 8 cümleyle özetle."))
            .put("contents", JSONArray().put(textObj("user", q)))
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        return textOf(post(body)).ifEmpty { "Sonuç bulamadım." }
    }

    // ================= telefon eylemleri =================
    private fun exec(name: String, a: JSONObject): String {
        return when (name) {
            "research" -> research(a.optString("query"))
            "read_webpage" -> readWebpage(a.optString("url"))
            "set_alarm" -> setAlarm(a.optInt("hour"), a.optInt("minute"), a.optString("label", "Jarvis"))
            "set_timer" -> launch(Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, a.optInt("seconds"))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true), "Sayaç kuruldu.")
            "add_event" -> addEvent(a.optString("title"), a.optString("start"), a.optInt("duration_min", 60))
            "list_events" -> listEvents(a.optInt("days", 3))
            "call" -> callContact(a.optString("contact"))
            "answer_call" -> answerCall()
            "end_call" -> endCall()
            "send_sms" -> sendSms(a.optString("contact"), a.optString("text"))
            "whatsapp_send" -> whatsapp(a.optString("contact"), a.optString("text"))
            "send_email" -> sendEmail(a.optString("to"), a.optString("subject"), a.optString("body"))
            "read_notifications" -> readNotifications()
            "open_app" -> openApp(a.optString("name"))
            "play_music" -> playMusic(a.optString("query"))
            "media" -> media(a.optString("action"))
            "set_volume" -> setVolume(a.optInt("percent"), a.optString("stream", "music"))
            "set_brightness" -> setBrightness(a.optInt("percent"))
            "flashlight" -> flashlight(a.optBoolean("on"))
            "navigate" -> navigate(a.optString("place"))
            "get_location" -> whereAmI()
            "open_url" -> launch(Intent(Intent.ACTION_VIEW, Uri.parse(a.optString("url"))), "Açıldı.")
            "open_settings" -> openSettings(a.optString("page"))
            "copy" -> copyText(a.optString("text"))
            "screen_on" -> screenOn()
            "phone_status" -> phoneStatus()
            "wait" -> { Thread.sleep(a.optInt("seconds", 1).coerceIn(1, 5) * 1000L); "Beklendi." }
            "global_action", "read_screen", "click", "long_click", "tap", "type_text", "scroll", "swipe" -> accessTool(name, a)
            else -> "Bilinmeyen araç: $name"
        }
    }

    private fun launch(i: Intent, ok: String): String = try {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val accCtx = JarvisAccess.inst
        if (accCtx != null) accCtx.startActivity(i) else startActivity(i)
        ok
    } catch (e: Exception) {
        "Başlatılamadı. Ekran üstü gösterme ve diğer izinleri kontrol edin. (${e.javaClass.simpleName})"
    }

    private fun accessTool(name: String, a: JSONObject): String {
        val acc = JarvisAccess.inst
            ?: return "Erişilebilirlik izni kapalı. Kullanıcı ayarlardan Jarvis erişilebilirliğini açmalı."
        return when (name) {
            "global_action" -> if (acc.global(a.optString("action"))) "Yapıldı." else "Yapılamadı."
            "read_screen" -> acc.readScreen()
            "click" -> if (acc.click(a.optString("text"))) "Dokunuldu." else "Bu yazıyı ekranda bulamadım."
            "long_click" -> if (acc.longClick(a.optString("text"))) "Uzun basıldı." else "Bu yazıyı bulamadım."
            "tap" -> if (acc.tap(a.optInt("x").toFloat(), a.optInt("y").toFloat())) "Dokunuldu." else "Dokunulamadı."
            "type_text" -> if (acc.type(a.optString("text"), a.optBoolean("submit"))) "Yazıldı." else "Yazı kutusu bulamadım."
            "swipe" -> if (acc.swipe(a.optString("direction"))) "Kaydırıldı." else "Hareket yapılamadı."
            else -> if (acc.scroll(a.optString("direction") != "up")) "Kaydırıldı." else "Kaydırılamadı."
        }
    }

    private fun setAlarm(h: Int, min: Int, label: String): String {
        if (h !in 0..23 || min !in 0..59) return "Saat anlaşılamadı."
        return launch(Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, min)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label).putExtra(AlarmClock.EXTRA_SKIP_UI, true),
            "Alarmı $h:${"%02d".format(min)} için kurdum efendim.")
    }

    private fun addEvent(title: String, start: String, dur: Int): String {
        return try {
            val t = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(start)?.time ?: return "Tarih anlaşılamadı."
            var calId = -1L
            contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID),
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= 500", null, null)?.use {
                if (it.moveToFirst()) calId = it.getLong(0)
            }
            if (calId < 0) return "Telefonda yazılabilir takvim bulunamadı."
            val v = ContentValues().apply {
                put(CalendarContract.Events.DTSTART, t)
                put(CalendarContract.Events.DTEND, t + dur.coerceAtLeast(1) * 60000L)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            contentResolver.insert(CalendarContract.Events.CONTENT_URI, v)
            "Takvime '$title' eklendi."
        } catch (e: Exception) { "Takvime eklenemedi: ${e.message}" }
    }

    private fun listEvents(days: Int): String {
        return try {
            val now = System.currentTimeMillis()
            val end = now + days.coerceIn(1, 30) * 86400000L
            val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(b, now)
            ContentUris.appendId(b, end)
            val sb = StringBuilder()
            contentResolver.query(b.build(),
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN),
                null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                val f = SimpleDateFormat("d MMM HH:mm", Locale("tr", "TR"))
                var k = 0
                while (c.moveToNext() && k < 15) {
                    sb.append(f.format(Date(c.getLong(1)))).append(" ").append(c.getString(0) ?: "").append("\n")
                    k++
                }
            }
            sb.toString().ifBlank { "Bu aralıkta etkinlik yok." }
        } catch (e: Exception) { "Takvim okunamadı: ${e.message}" }
    }

    private fun findContact(q: String): Pair<String, String>? {
        val t = q.trim()
        if (Regex("^[+0-9 ()\\-]{5,}$").matches(t)) return Pair(t, t)
        val nq = norm(t)
        if (nq.isBlank()) return null
        var partial: Pair<String, String>? = null
        contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val nm = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                val nn = norm(nm)
                if (nn == nq) return Pair(nm, num)
                if (partial == null && (nn.contains(nq) || nq.contains(nn))) partial = Pair(nm, num)
            }
        }
        return partial
    }

    private fun callContact(q: String): String {
        val c = findContact(q) ?: return "Rehberde '$q' bulunamadı."
        return launch(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(c.second))), "${c.first} aranıyor.")
    }

    private fun answerCall(): String = try {
        getSystemService(TelecomManager::class.java).acceptRingingCall()
        ringing = false
        "Arama açıldı."
    } catch (e: Exception) { "Aramayı açamadım: ${e.message}" }

    private fun endCall(): String = try {
        if (Build.VERSION.SDK_INT >= 28) {
            getSystemService(TelecomManager::class.java).endCall()
            ringing = false
            "Arama kapatıldı."
        } else "Bu Android sürümü desteklemiyor."
    } catch (e: Exception) { "Kapatamadım: ${e.message}" }

    @Suppress("DEPRECATION")
    private fun sendSms(contact: String, text: String): String {
        val c = findContact(contact) ?: return "Rehberde '$contact' bulunamadı."
        return try {
            val sm = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            sm.sendMultipartTextMessage(c.second, null, sm.divideMessage(text), null, null)
            "SMS ${c.first} kişisine gönderildi."
        } catch (e: Exception) { "SMS gönderilemedi: ${e.message}" }
    }

    private fun whatsapp(contact: String, text: String): String {
        val c = findContact(contact) ?: return "Rehberde '$contact' bulunamadı."
        var num = c.second.filter { it.isDigit() || it == '+' }
        num = when {
            num.startsWith("+") -> num.drop(1)
            num.startsWith("00") -> num.drop(2)
            num.startsWith("0") -> "90" + num.drop(1)
            num.length == 10 -> "90$num"
            else -> num
        }
        val uri = Uri.parse("https://wa.me/$num?text=" + URLEncoder.encode(text, "UTF-8").replace("+", "%20"))
        val r = launch(Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp"), "ok")
        if (r != "ok") return "WhatsApp açılamadı."
        val acc = JarvisAccess.inst ?: return "Mesaj WhatsApp'ta hazır ama erişilebilirlik kapalı, gönder'e basamadım."
        Thread.sleep(3500)
        return if (acc.click("Gönder") || acc.click("Send")) "WhatsApp mesajı ${c.first} kişisine gönderildi."
        else "Mesaj hazır ama gönder düğmesini bulamadım."
    }

    private fun sendEmail(to: String, subject: String, body: String): String {
        val r = launch(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, body), "ok")
        if (r != "ok") return r
        val acc = JarvisAccess.inst ?: return "E-posta taslağı hazır ama erişilebilirlik kapalı, gönderemedim."
        Thread.sleep(2500)
        return if (acc.click("Gönder") || acc.click("Send")) "E-posta gönderildi." else "Taslak hazır ama gönder düğmesini bulamadım."
    }

    private fun readNotifications(): String {
        val n = JarvisNotif.inst
            ?: return "Bildirim erişimi kapalı. Uygulamadaki BİLDİRİM İZNİ düğmesinden Jarvis'e izin verilmeli."
        return try {
            val sb = StringBuilder()
            for (s in n.activeNotifications.takeLast(15)) {
                if (s.packageName == packageName) continue
                val e = s.notification.extras
                val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                val text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
                val app = try {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(s.packageName, 0)).toString()
                } catch (x: Exception) { s.packageName }
                if (title.isNotBlank() || text.isNotBlank()) sb.append("$app: $title - $text\n")
            }
            sb.toString().ifBlank { "Bildirim yok." }
        } catch (e: Exception) { "Bildirimler okunamadı." }
    }

    private fun openApp(name: String): String {
        val nq = norm(name)
        val pm = packageManager
        val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val hit = list.firstOrNull { norm(it.loadLabel(pm).toString()) == nq }
            ?: list.firstOrNull { norm(it.loadLabel(pm).toString()).contains(nq) }
            ?: list.firstOrNull { val l = norm(it.loadLabel(pm).toString()); l.length >= 3 && nq.contains(l) }
            ?: return "'$name' adlı uygulamayı bulamadım."
        val pkg = hit.activityInfo.packageName
        val li = pm.getLaunchIntentForPackage(pkg) ?: return "Uygulama açılamadı."
        val r = launch(li, "${hit.loadLabel(pm)} açıldı.")
        val acc = JarvisAccess.inst
        if (acc != null && r.endsWith("açıldı.")) {
            Thread.sleep(1500)
            val cur = acc.rootInActiveWindow?.packageName?.toString()
            if (cur != null && cur != pkg)
                return "${hit.loadLabel(pm)} için komutu gönderdim. Açılmadıysa Ekran üstü iznini kontrol edin."
        }
        return r
    }

    private fun playMusic(q: String): String {
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .putExtra(android.app.SearchManager.QUERY, q)
        val r = launch(i, "ok")
        if (r == "ok") return "'$q' çalınıyor."
        return launch(Intent(Intent.ACTION_VIEW,
            Uri.parse("https://www.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8"))), "YouTube'da '$q' açıldı.")
    }

    private fun media(action: String): String {
        val code = when (action) {
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return "Yapıldı."
    }

    private fun setVolume(percent: Int, stream: String): String = try {
        val s = when (stream) { "ring" -> AudioManager.STREAM_RING; "alarm" -> AudioManager.STREAM_ALARM; else -> AudioManager.STREAM_MUSIC }
        audio.setStreamVolume(s, audio.getStreamMaxVolume(s) * percent.coerceIn(0, 100) / 100, 0)
        "Ses %$percent yapıldı."
    } catch (e: Exception) { "Ses ayarlanamadı: ${e.message}" }

    private fun setBrightness(percent: Int): String {
        if (!Settings.System.canWrite(this)) return "Ayar yazma izni kapalı. Uygulamadaki AYAR YAZMA düğmesinden izin verilmeli."
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 255 * percent.coerceIn(1, 100) / 100)
        return "Parlaklık %$percent yapıldı."
    }

    private fun flashlight(on: Boolean): String = try {
        val cm = getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        cm.setTorchMode(id, on)
        if (on) "Fener açıldı." else "Fener kapandı."
    } catch (e: Exception) { "Fener kullanılamadı." }

    private fun navigate(place: String): String {
        val r = launch(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(place)))
            .setPackage("com.google.android.apps.maps"), "ok")
        if (r == "ok") return "$place için yol tarifi başladı."
        return launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place))), "$place haritada açıldı.")
    }

    @Suppress("DEPRECATION")
    private fun whereAmI(): String {
        return try {
            val lm = getSystemService(LocationManager::class.java)
            var best: Location? = null
            for (pr in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
                try {
                    val l = lm.getLastKnownLocation(pr)
                    if (l != null && (best == null || l.time > best.time)) best = l
                } catch (e: SecurityException) {}
            }
            val loc = best ?: return "Konum bulunamadı. Konum izni ve GPS açık olmalı."
            var addr = ""
            try {
                if (Geocoder.isPresent()) {
                    val l = Geocoder(this, Locale("tr", "TR")).getFromLocation(loc.latitude, loc.longitude, 1)
                    if (!l.isNullOrEmpty()) addr = l[0].getAddressLine(0) ?: ""
                }
            } catch (e: Exception) {}
            "Konum: ${if (addr.isNotBlank()) addr else "adres bulunamadı"} (${loc.latitude}, ${loc.longitude})"
        } catch (e: Exception) { "Konum alınamadı: ${e.message}" }
    }

    private fun openSettings(page: String): String {
        val action = when (page) {
            "wifi" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return launch(Intent(action), "Ayar sayfası açıldı.")
    }

    private fun copyText(text: String): String = try {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("jarvis", text))
        "Panoya kopyalandı."
    } catch (e: Exception) { "Kopyalanamadı." }

    @Suppress("DEPRECATION")
    private fun screenOn(): String = try {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "jarvis:screen").acquire(5000)
        "Ekran açıldı."
    } catch (e: Exception) { "Ekran açılamadı." }

    private fun readWebpage(u: String): String = try {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        val html = c.inputStream.bufferedReader().use { it.readText().take(300000) }
        val txt = html.replace(Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?s)<[^>]+>"), " ")
            .replace(Regex("&[a-z#0-9]+;"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (txt.isEmpty()) "Sayfada okunabilir metin yok." else txt.take(5000)
    } catch (e: Exception) { "Sayfa okunamadı: ${e.message}" }

    private fun phoneStatus(): String {
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val st = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        val acc = if (JarvisAccess.inst != null) "açık" else "kapalı"
        val notif = if (JarvisNotif.inst != null) "açık" else "kapalı"
        return "Pil %${if (lvl >= 0) lvl * 100 / scale else -1}, ${if (charging) "şarj oluyor" else "şarjda değil"}. Erişilebilirlik $acc, bildirim erişimi $notif."
    }
}
