package com.pst.livetranslator

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/**
 * True continuous PCM capture. Vosk performs local streaming recognition.
 * Speaker diarization / overlapping speech separation are NOT provided.
 */
class OfflineStreaming(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun onStatus(value: String)
        fun onPartial(value: String)
        fun onFinal(value: String)
        fun onError(value: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val active = AtomicBoolean(false)
    private var capture: AudioRecord? = null
    private var worker: Thread? = null
    private val modelDir get() = File(context.filesDir, "vosk-model-small-en-us-0.15")
    fun installed() = File(modelDir, "am").exists()

    fun install(onFinished: (Boolean) -> Unit) {
        if (active.get()) { onFinished(false); return }
        thread(name = "vosk-model-install") {
            var success = false
            try {
                if (!installed()) {
                    val root = context.filesDir.canonicalFile
                    val url = URL("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip")
                    val connection = url.openConnection().apply {
                        connectTimeout = 20000
                        readTimeout = 30000
                    }
                    ZipInputStream(connection.getInputStream().buffered()).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            val destination = File(root, entry.name).canonicalFile
                            require(destination.path.startsWith(root.path + File.separator)) { "Invalid model path" }
                            if (entry.isDirectory) destination.mkdirs()
                            else {
                                destination.parentFile?.mkdirs()
                                destination.outputStream().buffered().use { zip.copyTo(it) }
                            }
                            zip.closeEntry()
                        }
                    }
                }
                success = installed()
            } catch (e: Exception) {
                main.post { listener.onError("Model indirilemedi: ${e.localizedMessage}") }
            }
            main.post { onFinished(success) }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!installed()) { listener.onError("Önce çevrimdışı İngilizce modeli indirin."); return }
        if (!active.compareAndSet(false, true)) return
        worker = thread(name = "vosk-pcm-stream") {
            try {
                Model(modelDir.absolutePath).use { model ->
                    Recognizer(model, 16000f).use { recognizer ->
                        val min = AudioRecord.getMinBufferSize(
                            16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                        )
                        require(min > 0) { "PCM buffer unavailable" }
                        val record = AudioRecord(
                            MediaRecorder.AudioSource.VOICE_RECOGNITION,
                            16000, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 8192)
                        )
                        capture = record
                        require(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
                        record.startRecording()
                        main.post { listener.onStatus("Çevrimdışı PCM dinleme aktif • 16 kHz") }
                        val bytes = ByteArray(4096)
                        var lastPartial = ""
                        while (active.get()) {
                            val count = record.read(bytes, 0, bytes.size)
                            if (count <= 0) continue
                            if (recognizer.acceptWaveForm(bytes, count)) {
                                val value = JSONObject(recognizer.result).optString("text").trim()
                                if (value.isNotEmpty()) main.post { listener.onFinal(value) }
                                lastPartial = ""
                            } else {
                                val value = JSONObject(recognizer.partialResult).optString("partial").trim()
                                if (value.isNotEmpty() && value != lastPartial) {
                                    lastPartial = value
                                    main.post { listener.onPartial(value) }
                                }
                            }
                        }
                        val tail = JSONObject(recognizer.finalResult).optString("text").trim()
                        if (tail.isNotEmpty()) main.post { listener.onFinal(tail) }
                    }
                }
            } catch (e: Exception) {
                main.post { listener.onError("PCM / Vosk: ${e.localizedMessage}") }
            } finally {
                try { capture?.stop() } catch (_: Exception) {}
                capture?.release()
                capture = null
                active.set(false)
            }
        }
    }

    fun stop() {
        active.set(false)
        try { capture?.stop() } catch (_: Exception) {}
    }
}
