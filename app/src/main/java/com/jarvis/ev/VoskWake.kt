package com.jarvis.ev

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream

/** Cihazda (internetsiz) uyandırma kelimesi dinleyici. Bip sesi yok. */
class VoskWake(
    private val ctx: Context,
    private val onBeat: () -> Unit,
    private val onText: (String) -> Unit,
    private val onPartial: (String) -> Unit
) {
    @Volatile private var run = false
    private var th: Thread? = null
    val alive: Boolean get() = run && th?.isAlive == true

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val m = model ?: return false
        val rate = 16000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        var rec: AudioRecord? = null
        for (src in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            try {
                val r = AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 8192) * 2)
                if (r.state == AudioRecord.STATE_INITIALIZED) { rec = r; break } else r.release()
            } catch (e: Exception) {}
        }
        if (rec == null) return false
        try { rec.startRecording() } catch (e: Exception) { rec.release(); return false }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) { rec.release(); return false }
        val r = rec
        run = true
        th = Thread {
            var recog: Recognizer? = null
            try {
                recog = Recognizer(m, rate.toFloat())
                val buf = ShortArray(2048)
                var lastBeatAt = 0L
                var lastPartial = ""
                while (run) {
                    val n = r.read(buf, 0, buf.size)
                    if (n < 0) break
                    if (n == 0) continue
                    val now = System.currentTimeMillis()
                    if (now - lastBeatAt > 1000) { lastBeatAt = now; onBeat() }
                    if (recog.acceptWaveForm(buf, n)) {
                        val t = JSONObject(recog.result).optString("text", "")
                        lastPartial = ""
                        if (t.isNotBlank() && run) onText(t)
                    } else {
                        val pt = JSONObject(recog.partialResult).optString("partial", "")
                        if (pt.isNotBlank() && pt != lastPartial && run) { lastPartial = pt; onPartial(pt) }
                    }
                }
            } catch (e: Throwable) {
                status = "dinleme HATA: " + e.javaClass.simpleName + " " + (e.message ?: "").take(150)
            } finally {
                run = false
                try { r.stop() } catch (e: Exception) {}
                r.release()
                try { recog?.close() } catch (e: Exception) {}
            }
        }.apply { isDaemon = true; start() }
        return true
    }

    fun stop() {
        run = false
        try { th?.join(400) } catch (e: Exception) {}
    }

    companion object {
        @Volatile var model: Model? = null
        @Volatile private var loading = false
        val ready: Boolean get() = model != null
        @Volatile var status = "yukleniyor"

        fun prepare(ctx: Context) {
            if (model != null || loading) return
            loading = true
            Thread {
                try {
                    val dir = File(ctx.filesDir, "vosk-model")
                    val flag = File(dir, ".ok")
                    if (!flag.exists()) {
                        dir.deleteRecursively()
                        copyAssets(ctx.assets, "vosk-model", dir)
                        flag.writeText("1")
                    }
                    status = "model yukleniyor"
                    model = Model(dir.absolutePath)
                    status = "hazir"
                } catch (e: Throwable) {
                    status = "HATA: " + e.javaClass.simpleName + " " + (e.message ?: "").take(150)
                } finally { loading = false }
            }.apply { isDaemon = true; start() }
        }

        private fun copyAssets(am: AssetManager, src: String, dst: File) {
            val list = am.list(src) ?: emptyArray()
            if (list.isEmpty()) {
                dst.parentFile?.mkdirs()
                am.open(src).use { i -> FileOutputStream(dst).use { o -> i.copyTo(o) } }
            } else {
                dst.mkdirs()
                for (f in list) copyAssets(am, "$src/$f", File(dst, f))
            }
        }
    }
}
