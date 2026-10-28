package com.jtliaw.screentranslator

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class TranslateResult(val original: String, val translation: String, val ok: Boolean)

/** 移植自 skylark_ai.py：同样的提示词、术语表、mediaResolution、假名过多重试一次 */
object Gemini {
    const val NO_TEXT = "（未识别到文字）"
    private val KANA = Regex("[\u3040-\u30ff]")

    fun buildSystem(source: String, target: String): String {
        val auto = source.isBlank() || source.contains("自动")
        val head = if (auto)
            "原文语言不固定，可能是日文、英文或其他语言，也可能混合出现，请自动识别。请把图片中的所有文字翻译成【$target】。"
        else
            "图片中的文字主要是【$source】，请把所有文字翻译成【$target】。"
        return "你是屏幕翻译工具。图片是游戏或软件界面的一部分。" + head +
                "忽略对话框的边框、装饰线条、图标，以及汉字上方的小字注音（振假名），只翻译正文大字。" +
                "日语常省略主语：请根据说话人和场景判断是谁在对谁说，不要把说话人和听话人弄反；假名同音词请结合上下文选词。" +
                "人名、地名、店名、商品名拿不准时，保留原文或只音译，不要按字面意译。" +
                "严格按下面两行格式输出，不要解释：\n原文：<你读到的正文，保持换行>\n译文：<译文，保持换行；已经是目标语言的部分原样保留>\n" +
                "若图中没有文字，输出：原文：无\n译文：（未识别到文字）"
    }

    private fun explain(ctx: Context, code: Int, body: String): String {
        val base = when (code) {
            400 -> ctx.getString(R.string.err_400)
            401 -> ctx.getString(R.string.err_401)
            403 -> ctx.getString(R.string.err_403)
            404 -> ctx.getString(R.string.err_404)
            429 -> ctx.getString(R.string.err_429)
            else -> ctx.getString(R.string.err_server, code)
        }
        return base + "\n" + body.take(200)
    }

    private fun call(ctx: Context, apiKey: String, model: String, system: String, jpeg: ByteArray, mediaRes: String): String {
        val parts = JSONArray()
            .put(JSONObject().put("text", "请翻译图片中的文字。"))
            .put(JSONObject().put("inline_data", JSONObject()
                .put("mime_type", "image/jpeg")
                .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))))
        val gc = JSONObject().put("temperature", 0.2)
        val mr = mediaRes.trim().lowercase()
        if (mr == "low" || mr == "medium" || mr == "high") gc.put("mediaResolution", "MEDIA_RESOLUTION_" + mr.uppercase())
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", gc)

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val c = url.openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 20000
            c.readTimeout = 60000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", apiKey)
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw Exception(explain(ctx, code, text))
            val cands = JSONObject(text).optJSONArray("candidates")
            if (cands == null || cands.length() == 0) throw Exception(ctx.getString(R.string.err_blocked) + "\n" + text.take(200))
            val ps = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                ?: throw Exception(ctx.getString(R.string.err_empty) + "\n" + text.take(200))
            val sb = StringBuilder()
            for (i in 0 until ps.length()) sb.append(ps.getJSONObject(i).optString("text", ""))
            return sb.toString()
        } finally {
            c.disconnect()
        }
    }

    private fun split(t0: String): Pair<String, String> {
        val t = t0.trim()
        val re = Regex("译文[：:]")
        val m = re.find(t)
        val z = if (m != null) t.substring(m.range.last + 1).trim() else t
        var o = ""
        val j = Regex("原文[：:]").find(t)
        if (j != null && m != null && m.range.first > j.range.last) {
            o = t.substring(j.range.last + 1, m.range.first).trim()
        }
        return Pair(o, if (z == "无" || z.isEmpty() || z.contains("未识别到文字")) NO_TEXT else z)
    }

    private fun kanaRatio(s: String): Double = KANA.findAll(s).count().toDouble() / maxOf(s.length, 1)

    fun translate(ctx: Context, apiKey: String, model: String, system: String, jpeg: ByteArray,
                  mediaRes: String, target: String): TranslateResult {
        var (o, z) = split(call(ctx, apiKey, model, system, jpeg, mediaRes))
        if (target.contains("日")) return TranslateResult(o, z, true)
        if (kanaRatio(z) < 0.2) return TranslateResult(o, z, true)
        // 译文里假名太多：模型把原文抄回来了，重试一次
        val p = split(call(ctx, apiKey, model, system, jpeg, mediaRes))
        o = p.first; z = p.second
        return TranslateResult(o, z, kanaRatio(z) < 0.2)
    }
}
