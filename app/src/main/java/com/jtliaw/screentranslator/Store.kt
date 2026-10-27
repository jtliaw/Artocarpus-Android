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
