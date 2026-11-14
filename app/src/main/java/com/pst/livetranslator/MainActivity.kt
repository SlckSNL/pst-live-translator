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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.common.model.DownloadConditions
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var original: TextView
    private lateinit var translated: TextView
    private lateinit var toggle: Button
    private lateinit var translator: Translator
    private var speech: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var modelReady = false
    private var ttsReady = false
    private var running = false
    private var listening = false
    private var busy = false
    private var epoch = 0
    private var pending: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 35, 28, 28)
        }
        fun label(value: String, size: Float): TextView = TextView(this).apply {
            text = value
            textSize = size
            setPadding(0, 14, 0, 14)
        }
        layout.addView(label("PST LIVE TRANSLATOR", 24f))
        status = label("Hazırlanıyor...", 15f)
        layout.addView(status)
        toggle = Button(this).apply {
            text = "HAZIRLANIYOR"
            isEnabled = false
            setOnClickListener { if (running) stopTranslation() else startTranslation() }
        }
        layout.addView(toggle)
        layout.addView(label("İngilizce konuşma", 18f))
        original = label("Henüz konuşma yok", 17f)
        layout.addView(original)
        layout.addView(label("Türkçe çeviri", 18f))
        translated = label("Çeviri burada görünecek", 19f)
        layout.addView(translated)
        layout.addView(label("Telefon mikrofonu İngilizce konuşmaları dinler. Türkçe seslendirme bağlı Bluetooth medya çıkışına yönlendirilir. İlk çeviri modeli indirme işlemi internet gerektirir.", 13f))
        setContentView(ScrollView(this).apply { addView(layout) })
        translator = Translation.getClient(TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.TURKISH).build())
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { modelReady = true; refresh() }
            .addOnFailureListener { status.text = "Model indirilemedi: ${it.localizedMessage}" }
        tts = TextToSpeech(this, this)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
    }

    override fun onInit(code: Int) {
        if (code == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.forLanguageTag("tr-TR")) ?: TextToSpeech.LANG_NOT_SUPPORTED
            ttsReady = result != TextToSpeech.LANG_NOT_SUPPORTED && result != TextToSpeech.LANG_MISSING_DATA
            tts?.setSpeechRate(1.12f)
        }
        refresh()
    }

    private fun refresh() {
        if (running) return
        val available = SpeechRecognizer.isRecognitionAvailable(this)
        status.text = when {
            !modelReady -> "Çeviri modeli indiriliyor..."
            !ttsReady -> "Türkçe TTS sesi eksik; telefon ayarlarından yükleyin."
            !available -> "Konuşma tanıma servisi bulunamadı."
            else -> "Hazır. Kulaklığı bağlayıp başlatın."
        }
        toggle.isEnabled = modelReady && ttsReady && available
        toggle.text = "ÇEVİRİYİ BAŞLAT"
    }

    private fun startTranslation() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
            return
        }
        running = true
        epoch++
        toggle.text = "DURDUR"
        schedule(0)
    }

    private fun schedule(delay: Long) {
        pending?.let(handler::removeCallbacks)
        val token = epoch
        pending = Runnable { if (running && token == epoch && !busy && !listening) listen() }
        handler.postDelayed(pending!!, delay)
    }

    private fun listen() {
        if (!running || listening || busy) return
        speech?.destroy()
        speech = SpeechRecognizer.createSpeechRecognizer(this)
        speech?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { status.text = "Dinleniyor..." }
            override fun onBeginningOfSpeech() { status.text = "Konuşma algılandı..." }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { status.text = "Konuşma işleniyor..." }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.let { original.text = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) {
                listening = false
                if (running) {
                    status.text = "Dinleme yeniden başlatılıyor ($error)"
                    schedule(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1400 else 700)
                }
            }
            override fun onResults(results: Bundle?) {
                listening = false
                val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (words.isNullOrBlank()) { schedule(400); return }
                original.text = words
                busy = true
                status.text = "Çevriliyor..."
                val token = epoch
                translator.translate(words).addOnSuccessListener { value ->
                    if (running && token == epoch) {
                        translated.text = value
                        status.text = "Türkçe seslendiriliyor..."
                        tts?.speak(value, TextToSpeech.QUEUE_ADD, null, "pst-$token-${System.nanoTime()}")
                    }
                    busy = false
                    schedule(600)
                }.addOnFailureListener {
                    if (running && token == epoch) status.text = "Çeviri hatası: ${it.localizedMessage}"
                    busy = false
                    schedule(1100)
                }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            listening = true
            speech?.startListening(intent)
        } catch (e: Exception) {
            listening = false
            status.text = "Mikrofon hatası: ${e.localizedMessage}"
            schedule(1200)
        }
    }

    private fun stopTranslation() {
        running = false
        epoch++
        busy = false
        listening = false
        pending?.let(handler::removeCallbacks)
        speech?.cancel()
        speech?.destroy()
        speech = null
        tts?.stop()
        refresh()
    }

    override fun onDestroy() {
        stopTranslation()
        translator.close()
        tts?.shutdown()
        super.onDestroy()
    }
}