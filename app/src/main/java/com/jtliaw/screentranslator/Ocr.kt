package com.jtliaw.screentranslator

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.math.max

/** 离线 OCR（只认英文/拉丁字母）+ 离线翻译，免费，不需要密钥 */
object Ocr {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    fun run(ctx: Context, src: Bitmap, targetCode: String): TranslateResult {
        try {
            // 游戏字小，放大后识别更准
            var bmp = src
            val m = max(src.width, src.height)
            if (m < 900) {
                val k = minOf(4f, 900f / m)
                bmp = Bitmap.createScaledBitmap(src, (src.width * k).toInt(), (src.height * k).toInt(), true)
            }
            val vt = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)), 30, TimeUnit.SECONDS)
            val blocks = vt.textBlocks
                .map { b -> b.lines.joinToString(" ") { it.text }.trim() }
                .filter { it.isNotEmpty() }
            if (blocks.isEmpty()) return TranslateResult("无", Gemini.NO_TEXT, true)
            val original = blocks.joinToString("\n")
            if (targetCode == "en") return TranslateResult(original, original, true)

            val tr = Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage("en").setTargetLanguage(targetCode).build())
            try {
                Tasks.await(tr.downloadModelIfNeeded(DownloadConditions.Builder().build()), 180, TimeUnit.SECONDS)
                val out = blocks.joinToString("\n") { Tasks.await(tr.translate(it), 30, TimeUnit.SECONDS) }
                return TranslateResult(original, out, true)
            } finally {
                tr.close()
            }
        } catch (e: ExecutionException) {
            val msg = (e.cause ?: e).message ?: ""
            if (msg.contains("download", true) || msg.contains("network", true) || msg.contains("resolve", true))
                throw Exception(ctx.getString(R.string.err_model_download))
            throw Exception(ctx.getString(R.string.err_ocr, msg))
        }
    }
}
