package com.jtliaw.screentranslator

import android.content.Context
import org.json.JSONObject
import java.io.File

object Cfg {
    fun sp(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    fun str(c: Context, k: String, d: String): String = sp(c).getString(k, d) ?: d
}

object History {
    private val lock = Any()
    private fun file(c: Context) = File(c.filesDir, "history.jsonl")

    fun add(c: Context, original: String, translation: String) {
        synchronized(lock) {
            val o = JSONObject().put("t", System.currentTimeMillis()).put("o", original).put("z", translation)
            file(c).appendText(o.toString() + "\n")
        }
    }

    fun load(c: Context, n: Int): List<JSONObject> {
        synchronized(lock) {
            val f = file(c)
            if (!f.exists()) return emptyList()
            return f.readLines().takeLast(n).mapNotNull {
                try { JSONObject(it) } catch (_: Exception) { null }
            }.reversed()
        }
    }

    fun clear(c: Context) {
        synchronized(lock) { file(c).delete() }
    }
}

class Lang(val label: String, val gem: String, val code: String)

object Langs {
    val all = listOf(
        Lang("简体中文", "简体中文", "zh"),
        Lang("繁體中文", "繁体中文", "zh"),
        Lang("English 英语", "英语", "en"),
        Lang("日本語 日语", "日语", "ja"),
        Lang("한국어 韩语", "韩语", "ko"),
        Lang("Bahasa Melayu 马来语", "马来语", "ms"),
        Lang("Bahasa Indonesia 印尼语", "印尼语", "id"),
        Lang("Español 西班牙语", "西班牙语", "es"),
        Lang("Français 法语", "法语", "fr"),
        Lang("Deutsch 德语", "德语", "de")
    )
    fun find(label: String): Lang = all.firstOrNull { it.label == label } ?: all[0]
}
