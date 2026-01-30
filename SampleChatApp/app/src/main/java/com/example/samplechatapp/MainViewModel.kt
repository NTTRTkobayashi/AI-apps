package com.example.samplechatapp

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing

    private val _showDeleteDialog = MutableStateFlow(false)
    val showDeleteDialog: StateFlow<Boolean> = _showDeleteDialog

    private val _showToast = MutableStateFlow<String?>(null)
    val showToast: StateFlow<String?> = _showToast

    private var speechRecognizer: SpeechRecognizer? = null
    private var sessionEndAtMillis: Long? = null
    private var handler: Handler? = null
    private var timeoutRunnable: Runnable? = null
    private var recordingStartDate: String? = null

    fun setupSpeechRecognizer(context: Context) {
        if (speechRecognizer == null && SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            handler = Handler(Looper.getMainLooper())
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { maybeRestartRecognition(context) }
                override fun onError(error: Int) { maybeRestartRecognition(context) }
                override fun onResults(results: android.os.Bundle?) {
                    val data = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = data?.joinToString(separator = " ") ?: ""
                    if (text.isNotBlank()) {
                        _recognizedText.value = listOf(_recognizedText.value, text).filter { it.isNotBlank() }.joinToString(" ")
                    }
                    maybeRestartRecognition(context)
                }
                override fun onPartialResults(partialResults: android.os.Bundle?) {}
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })
        }
    }

    fun startListening(context: Context) {
        setupSpeechRecognizer(context)
        _recognizedText.value = ""
        _isListening.value = true
        // 録音開始日時を記録（yyyy-MM-dd形式）
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        recordingStartDate = sdf.format(Date())
        val endAt = SystemClock.elapsedRealtime() + 5 * 60 * 1000L
        sessionEndAtMillis = endAt
        timeoutRunnable?.let { handler?.removeCallbacks(it) }
        val r = Runnable { if (_isListening.value) stopListening() }
        timeoutRunnable = r
        handler?.postDelayed(r, 5 * 60 * 1000L)
        beginRecognition(context)
    }

    fun stopListening() {
        _isListening.value = false
        sessionEndAtMillis = null
        timeoutRunnable?.let { handler?.removeCallbacks(it) }
        timeoutRunnable = null
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (_: Exception) {}
    }

    private fun beginRecognition(context: Context) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ja-JP")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            }
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {}
    }

    private fun withinSession(): Boolean {
        val end = sessionEndAtMillis ?: return false
        return SystemClock.elapsedRealtime() < end
    }

    private fun maybeRestartRecognition(context: Context) {
        if (_isListening.value && withinSession()) {
            beginRecognition(context)
        } else {
            stopListening()
        }
    }

    fun showDeleteDialog(show: Boolean) {
        _showDeleteDialog.value = show
    }

    fun deleteText() {
        _recognizedText.value = ""
        _showDeleteDialog.value = false
    }

    fun createMinutesAndPdf(context: Context) {
        _isProcessing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val apiUrl = "https://api.anthropic.com/v1/messages"
                val dateStr = recordingStartDate ?: SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val template = """
1. 議題
2. 日時
   $dateStr
3. 議事内容
4. まとめ
""".trimIndent()
                val prompt = """
以下のテンプレートに従い、議事録のみを日本語で出力してください。余計な説明や挨拶は不要です。
各項目の見出し（議題、日時、議事内容、まとめ）は必ず行頭に『■』を付けてください。
サブ項目は『・』で始めてください。
見出しやサブ項目が分かりやすいように出力してください。
---
$template
---

内容：${_recognizedText.value}
""".trimIndent()
                val apiKey = ""
                val json = JSONObject().apply {
                    put("model", "claude-3-haiku-20240307")
                    put("max_tokens", 1024)
                    put("messages", org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        })
                    })
                }
                val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val summary = try {
                    val request = Request.Builder()
                        .url(apiUrl)
                        .addHeader("x-api-key", apiKey)
                        .addHeader("anthropic-version", "2023-06-01")
                        .addHeader("Content-Type", "application/json")
                        .post(body)
                        .build()
                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string()
                        _showToast.value = "AI通信でエラーが発生しました"
                        throw Exception("Claude API error: ${response.code} ${response.message} $errorBody")
                    }
                    val resultJson = JSONObject(response.body?.string() ?: "")
                    val contentArr = resultJson.optJSONArray("content")
                    if (contentArr != null && contentArr.length() > 0) {
                        contentArr.getJSONObject(0).optString("text", "")
                    } else {
                        ""
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MainViewModel", "Claude API通信エラー", e)
                    _showToast.value = "AI通信でエラーが発生しました"
                    return@launch
                }
                if (summary.isBlank()) {
                    android.util.Log.e("MainViewModel", "AIから議事録が返りませんでした")
                    _showToast.value = "AIから議事録が返りませんでした"
                    return@launch
                }
                // PDF生成
                try {
                    val pdfDoc = PdfDocument()
                    val pageHeight = 842
                    val pageWidth = 595
                    val marginTop = 40f
                    val marginLeft = 40f
                    val lineSpacingLarge = 32f
                    val lineSpacingNormal = 24f
                    var y = marginTop
                    var pageNum = 1
                    var page = pdfDoc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
                    var canvas = page.canvas
                    val paint = android.graphics.Paint()
                    val maxWidth = pageWidth - marginLeft * 2
                    val lines = summary.split('\n').flatMap { line ->
                        // 長い行は自動で改行
                        if (paint.measureText(line) <= maxWidth) {
                            listOf(line)
                        } else {
                            val result = mutableListOf<String>()
                            var current = ""
                            for (word in line) {
                                val next = current + word
                                if (paint.measureText(next) > maxWidth && current.isNotEmpty()) {
                                    result.add(current)
                                    current = word.toString()
                                } else {
                                    current = next
                                }
                            }
                            if (current.isNotEmpty()) result.add(current)
                            result
                        }
                    }
                    for (line in lines) {
                        val spacing = when {
                            line.startsWith("■") -> lineSpacingLarge
                            line.startsWith("・") || line.startsWith("-") -> lineSpacingNormal
                            else -> lineSpacingNormal
                        }
                        if (y + spacing > pageHeight - marginTop) {
                            pdfDoc.finishPage(page)
                            pageNum++
                            page = pdfDoc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
                            canvas = page.canvas
                            y = marginTop
                        }
                        when {
                            line.startsWith("■") -> {
                                paint.textSize = 20f
                                paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
                                canvas.drawText(line, marginLeft, y, paint)
                            }
                            line.startsWith("・") || line.startsWith("-") -> {
                                paint.textSize = 14f
                                paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
                                canvas.drawText(line, marginLeft + 40f, y, paint)
                            }
                            else -> {
                                paint.textSize = 14f
                                paint.typeface = android.graphics.Typeface.DEFAULT
                                canvas.drawText(line, marginLeft, y, paint)
                            }
                        }
                        y += spacing
                    }
                    pdfDoc.finishPage(page)
                    val titleRegex = Regex("^1\\. *議題 [:：]?(.+)", RegexOption.MULTILINE)
                    val titleMatch = titleRegex.find(summary)
                    val rawTitle = titleMatch?.groupValues?.getOrNull(1)?.trim() ?: "meeting_summary_${System.currentTimeMillis()}"
                    val safeTitle = rawTitle.replace(Regex("[/\\:*?\"<>|\n\r]"), "_").take(30)
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                    if (!dir.exists()) dir.mkdirs()
                    val file = File(dir, "${safeTitle}.pdf")
                    FileOutputStream(file).use { pdfDoc.writeTo(it) }
                    pdfDoc.close()
                    _showToast.value = "PDF保存完了: ${file.name}"
                } catch (e: Exception) {
                    android.util.Log.e("MainViewModel", "PDF生成エラー", e)
                    _showToast.value = "PDF生成でエラーが発生しました"
                }
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun clearToast() {
        _showToast.value = null
    }
}
