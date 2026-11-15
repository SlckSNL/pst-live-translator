package com.pst.livetranslator

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.view.WindowManager
import android.graphics.Typeface
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.ArrayDeque
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var english: TextView
    private lateinit var turkish: TextView
    private lateinit var signal: TextView
    private lateinit var toggle: Button
    private lateinit var history: TextView
    private lateinit var voiceEnabled: CheckBox
    private lateinit var translator: Translator
    private var recognizer: SpeechRecognizer? = null
    private lateinit var offline: OfflineStreaming
    private lateinit var offlineModeButton: Button
    private var offlineMode = false
    private var tts: TextToSpeech? = null
    private var modelReady = false
    private var ttsReady = false
    private var running = false
    private var listening = false
    private var speaking = false
    private var generation = 0
    private var retries = 0
    private var pendingRestart: Runnable? = null
    private var partialFallback: Runnable? = null
    private var partialText = ""
    private var lastSubmitted = ""
    private var lastSubmittedAt = 0L
    private var lastTranslated = ""
    private var translating = false
    private val translations = ArrayDeque<String>()
    private val voiceQueue = ArrayDeque<String>()
    private val transcript = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 32)
        }
        fun label(value: String, size: Float) = TextView(this).apply {
            text = value
            textSize = size
            setPadding(0, 10, 0, 10)
        }
        root.addView(label("PST LIVE • MEETING", 22f))
        status = label("Başlatılıyor...", 15f)
        root.addView(status)
        signal = label("Mikrofon: bekleniyor", 13f)
        root.addView(signal)
        toggle = Button(this).apply {
            text = "HAZIRLANIYOR"
            isEnabled = false
            setOnClickListener { if (running) stopSession() else startSession() }
        }
        root.addView(toggle)
        offlineModeButton = Button(this).apply {
            text = "MOD: ANDROID KONUŞMA TANIMA"
            setOnClickListener {
                if (!running) {
                    offlineMode = !offlineMode
                    text = if (offlineMode) "MOD: SÜREKLİ ÇEVRİMDIŞI PCM" else "MOD: ANDROID KONUŞMA TANIMA"
                    updateReady()
                }
            }
        }
        root.addView(offlineModeButton)
        root.addView(Button(this).apply {
            text = "VOSK İNGİLİZCE MODELİNİ İNDİR"
            setOnClickListener {
                isEnabled = false
                status.text = "İngilizce model indiriliyor..."
                offline.install { ok ->
                    isEnabled = true
                    text = if (ok) "VOSK MODELİ HAZIR" else "MODEL İNDİRMEYİ TEKRAR DENE"
                    updateReady()
                }
            }
        })
        voiceEnabled = CheckBox(this).apply {
            text = "Türkçe seslendirme (Bluetooth)"
            isChecked = true
        }
        root.addView(voiceEnabled)
        root.addView(label("TÜRKÇE CANLI ÇEVİRİ", 18f))
        turkish = label("Konuşma bekleniyor...", 38f).apply {
            setTypeface(null, Typeface.BOLD)
            setLineSpacing(10f, 1.15f)
            setTextIsSelectable(true)
            minHeight = 250
        }
        root.addView(turkish)
        root.addView(label("Algılanan İngilizce", 14f))
        english = label("—", 15f)
        root.addView(english)
        val historyToggle = Button(this).apply { text = "TRANSKRİPTİ GÖSTER" }
        root.addView(historyToggle)
        history = label("", 13f).apply { visibility = View.GONE }
        root.addView(history)
        historyToggle.setOnClickListener {
            history.visibility = if (history.visibility == View.GONE) View.VISIBLE else View.GONE
            historyToggle.text = if (history.visibility == View.GONE) "TRANSKRİPTİ GÖSTER" else "TRANSKRİPTİ GİZLE"
        }
        root.addView(Button(this).apply {
            text = "DÖKÜMÜ PAYLAŞ"
            setOnClickListener {
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, transcript.joinToString("\n"))
                }
                startActivity(Intent.createChooser(share, "Dökümü paylaş"))
            }
        })
        root.addView(label("Tek telefon mikrofonu, üst üste konuşan kişileri ayrı ses kanallarına ayıramaz. Konuşmacıların sırayla konuşması doğruluğu artırır.", 12f))
        setContentView(ScrollView(this).apply { addView(root) })
        offline = OfflineStreaming(this, object : OfflineStreaming.Listener {
            override fun onStatus(value: String) { if (running && offlineMode) status.text = value }
            override fun onPartial(value: String) {
                if (running && offlineMode) { english.text = value; partialText = value }
            }
            override fun onFinal(value: String) {
                if (running && offlineMode) submit(value, generation)
            }
            override fun onError(value: String) { status.text = value }
        })

        translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.TURKISH)
                .build()
        )
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                modelReady = true
                updateReady()
            }
            .addOnFailureListener {
                status.text = "Çeviri modeli hatası: ${it.localizedMessage}"
                turkish.text = "Çeviri modeli yüklenemedi. İnterneti kontrol edin."
            }
        tts = TextToSpeech(this, this)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
        }
    }

    override fun onInit(result: Int) {
        if (result == TextToSpeech.SUCCESS) {
            val code = tts?.setLanguage(Locale.forLanguageTag("tr-TR"))
            ttsReady = code != TextToSpeech.LANG_MISSING_DATA && code != TextToSpeech.LANG_NOT_SUPPORTED
            tts?.setSpeechRate(1.18f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    handler.post { speaking = false; speakNext() }
                }
                override fun onError(utteranceId: String?) {
                    handler.post { speaking = false; speakNext() }
                }
            })
        }
        updateReady()
    }

    private fun updateReady() {
        if (running || !::toggle.isInitialized) return
        val speechReady = SpeechRecognizer.isRecognitionAvailable(this)
        toggle.isEnabled = modelReady && (if (offlineMode) offline.installed() else speechReady)
        toggle.text = "ÇEVİRİYİ BAŞLAT"
        status.text = when {
            !modelReady -> "Türkçe çeviri modeli hazırlanıyor..."
            offlineMode && !offline.installed() -> "Önce Vosk İngilizce modelini indirin."
            !offlineMode && !speechReady -> "Android konuşma tanıma servisi bulunamadı."
            !ttsReady -> "Metin çevirisi hazır; Türkçe seslendirme kullanılamayabilir."
            else -> "Hazır • İngilizce dinleme / Türkçe çeviri"
        }
    }

    private fun startSession() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
            return
        }
        running = true
        generation++
        retries = 0
        partialText = ""
        lastSubmitted = ""
        lastTranslated = ""
        translations.clear()
        voiceQueue.clear()
        transcript.clear()
        toggle.text = "DURDUR"
        turkish.text = "Dinleniyor..."
        if (offlineMode) offline.start() else restart(0)
    }

    private fun restart(delay: Long) {
        pendingRestart?.let { handler.removeCallbacks(it) }
        val token = generation
        val action = Runnable {
            if (running && token == generation && !listening) listen(token)
        }
        pendingRestart = action
        handler.postDelayed(action, delay)
    }

    private fun listen(token: Int) {
        if (!running || listening || token != generation) return
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                retries = 0
                status.text = "Dinleniyor • İngilizce toplantı"
            }
            override fun onBeginningOfSpeech() { status.text = "Konuşma algılandı" }
            override fun onRmsChanged(rmsdB: Float) {
                signal.text = "Mikrofon: " + when {
                    rmsdB >= 7f -> "Güçlü sinyal"
                    rmsdB >= 1f -> "Orta sinyal"
                    else -> "Zayıf sinyal"
                }
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { status.text = "Konuşma tamamlanıyor..." }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(results: Bundle?) {
                if (token != generation || !running) return
                val value = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                if (value.isNotEmpty()) {
                    english.text = value
                    partialText = value
                    partialFallback?.let { handler.removeCallbacks(it) }
                    val captured = value
                    val fallback = Runnable {
                        if (running && token == generation && partialText == captured && captured.length >= 16) {
                            submit(captured, token)
                        }
                    }
                    partialFallback = fallback
                    handler.postDelayed(fallback, 1300)
                }
            }
            override fun onResults(results: Bundle?) {
                listening = false
                partialFallback?.let { handler.removeCallbacks(it) }
                val finalText = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty().ifEmpty { partialText }
                if (finalText.isNotBlank()) submit(finalText, token)
                partialText = ""
                restart(120)
            }
            override fun onError(error: Int) {
                listening = false
                partialFallback?.let { handler.removeCallbacks(it) }
                if (!running || token != generation) return
                if (partialText.isNotBlank()) submit(partialText, token)
                partialText = ""
                retries = (retries + 1).coerceAtMost(6)
                status.text = "Dinleme yeniden bağlanıyor (kod $error)"
                val delay = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                    error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS) 1800L else
                    (250L * retries).coerceAtMost(1500L)
                restart(delay)
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 650L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 450L)
        }
        try {
            listening = true
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            listening = false
            status.text = "Mikrofon başlatılamadı: ${e.localizedMessage}"
            restart(1000)
        }
    }

    private fun submit(text: String, token: Int) {
        if (!running || token != generation || !modelReady) return
        val clean = text.trim()
        if (clean.length < 3) return
        val now = System.currentTimeMillis()
        if (clean.equals(lastSubmitted, ignoreCase = true) && now - lastSubmittedAt < 9000L) return
        lastSubmitted = clean
        lastSubmittedAt = now
        english.text = clean
        transcript.add("EN: $clean")
        if (translations.size >= 8) translations.removeFirst()
        translations.addLast(clean)
        translateNext(token)
    }

    private fun translateNext(token: Int) {
        if (translating || translations.isEmpty() || !running || token != generation) return
        translating = true
        val input = translations.removeFirst()
        status.text = "Türkçeye çevriliyor..."
        translator.translate(input)
            .addOnSuccessListener { output ->
                if (running && token == generation) {
                    lastTranslated = output
                    turkish.text = output
                    transcript.add("TR: $output")
                    if (history.visibility == View.VISIBLE) {
                        history.text = transcript.takeLast(40).joinToString("\n")
                    }
                    status.text = "Türkçe çeviri hazır • dinleme sürüyor"
                    if (voiceEnabled.isChecked && ttsReady) {
                        if (voiceQueue.size >= 5) voiceQueue.removeFirst()
                        voiceQueue.addLast(output)
                        speakNext()
                    }
                }
                translating = false
                translateNext(token)
            }
            .addOnFailureListener { error ->
                if (running && token == generation) {
                    status.text = "Çeviri hatası: ${error.localizedMessage}"
                    turkish.text = "Çeviri başarısız. Algılanan İngilizce: $input"
                }
                translating = false
                translateNext(token)
            }
    }

    private fun speakNext() {
        if (!running || speaking || !ttsReady || !voiceEnabled.isChecked || voiceQueue.isEmpty()) return
        speaking = true
        val value = voiceQueue.removeFirst()
        val result = tts?.speak(value, TextToSpeech.QUEUE_FLUSH, null, "pst-" + System.nanoTime())
        if (result != TextToSpeech.SUCCESS) speaking = false
    }

    private fun stopSession() {
        running = false
        generation++
        listening = false
        pendingRestart?.let { handler.removeCallbacks(it) }
        partialFallback?.let { handler.removeCallbacks(it) }
        offline.stop()
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        speaking = false
        translations.clear()
        voiceQueue.clear()
        translating = false
        updateReady()
    }

    override fun onDestroy() {
        stopSession()
        translator.close()
        tts?.shutdown()
        super.onDestroy()
    }
}
