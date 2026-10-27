package com.jtliaw.screentranslator

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var fKey: EditText
    private lateinit var fModel: EditText
    private lateinit var fSource: EditText
    private lateinit var fTarget: EditText
    private lateinit var fMediaRes: EditText
    private lateinit var fFont: EditText
    private lateinit var fExtra: EditText
    private lateinit var fGloss: EditText
    private lateinit var cTap: CheckBox

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val sp = Cfg.sp(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(24), dp(16), dp(32))
        val sv = ScrollView(this)
        sv.addView(root)
        setContentView(sv)

        fun label(t: String) {
            val v = TextView(this)
            v.text = t; v.textSize = 14f
            v.setTypeface(null, Typeface.BOLD)
            v.setPadding(0, dp(14), 0, dp(2))
            root.addView(v)
        }
        fun field(value: String, hint: String, multi: Boolean = false): EditText {
            val e = EditText(this)
            e.setText(value); e.hint = hint
            if (multi) {
                e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                e.minLines = 6; e.gravity = Gravity.TOP
            } else {
                e.setSingleLine(true)
            }
            root.addView(e)
            return e
        }
        fun button(t: String, onClick: () -> Unit) {
            val bt = Button(this)
            bt.text = t
            bt.setOnClickListener { onClick() }
            root.addView(bt)
        }

        val title = TextView(this)
        title.text = "屏幕翻译（Gemini）"; title.textSize = 22f
        root.addView(title)

        label("Gemini API 密钥（每人用自己的，aistudio.google.com/app/apikey 免费申请）")
        fKey = field(sp.getString("key", "") ?: "", "AIza…")
        fKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

        label("模型")
        fModel = field(sp.getString("model", "gemini-flash-lite-latest") ?: "", "gemini-flash-lite-latest")

        label("原文语言（写“自动识别”或“日语”等）")
        fSource = field(sp.getString("source", "自动识别") ?: "", "自动识别")

        label("译文语言")
        fTarget = field(sp.getString("target", "简体中文") ?: "", "简体中文")

        label("图片精度 low / medium / high / off（low 最省额度）")
        fMediaRes = field(sp.getString("mediaRes", "low") ?: "low", "low")

        label("译文字号")
        fFont = field(sp.getString("fontSize", "18") ?: "18", "18")
        fFont.inputType = InputType.TYPE_CLASS_NUMBER

        label("补充说明（可选，例如游戏名、人物关系）")
        fExtra = field(sp.getString("extra", "") ?: "", "")

        label("术语表（每行 原文=译文，# 开头是注释）")
        fGloss = field(sp.getString("glossary", "") ?: "", "例：ミルフィ=米露菲", multi = true)
        button("从文件导入术语表（术语表.txt）") {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "text/*"
            startActivityForResult(i, 3)
        }

        cTap = CheckBox(this)
        cTap.text = "点击蓝色框就翻译（这次点击不会传给游戏；关掉则只用悬浮条的“重译”）"
        cTap.isChecked = sp.getBoolean("tapFrame", true)
        root.addView(cTap)

        label("开始")
        button("启动悬浮翻译") { startFlow() }
        button("停止悬浮翻译") {
            if (TranslateService.running) {
                startService(Intent(this, TranslateService::class.java).setAction(TranslateService.ACTION_STOP))
            } else toast("没有在运行")
        }
        button("翻译历史") { showHistory() }

        label("荣耀 / MagicOS 防杀后台")
        button("打开本 App 的系统设置页（悬浮窗、自启动、电池）") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        button("电池优化设置（把本 App 设为“不受限制/不优化”）") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }

    private fun saveAll() {
        Cfg.sp(this).edit()
            .putString("key", fKey.text.toString().trim())
            .putString("model", fModel.text.toString().trim())
            .putString("source", fSource.text.toString().trim())
            .putString("target", fTarget.text.toString().trim())
            .putString("mediaRes", fMediaRes.text.toString().trim())
            .putString("fontSize", fFont.text.toString().trim())
            .putString("extra", fExtra.text.toString())
            .putString("glossary", fGloss.text.toString())
            .putBoolean("tapFrame", cTap.isChecked)
            .apply()
    }

    override fun onPause() {
        saveAll()
        super.onPause()
    }

    private fun startFlow() {
        saveAll()
        if (fKey.text.toString().isBlank()) { toast("请先填 API 密钥"); return }
        if (!Settings.canDrawOverlays(this)) {
            toast("请允许“显示在其他应用上层”，然后回来再点一次启动")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1) {
            if (resultCode == RESULT_OK && data != null) {
                val i = Intent(this, TranslateService::class.java)
                    .setAction(TranslateService.ACTION_START)
                    .putExtra(TranslateService.EXTRA_CODE, resultCode)
                    .putExtra(TranslateService.EXTRA_DATA, data)
                startForegroundService(i)
                moveTaskToBack(true)
            } else {
                toast("没有授权截屏")
            }
        } else if (requestCode == 3 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                val t = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                val old = fGloss.text.toString()
                fGloss.setText(if (old.isBlank()) t else old.trimEnd() + "\n" + t)
                toast("已导入")
            } catch (e: Exception) {
                toast("导入失败：" + e.message)
            }
        }
    }

    private fun showHistory() {
        val items = History.load(this, 100)
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val sb = StringBuilder()
        for (o in items) {
            sb.append(fmt.format(Date(o.optLong("t")))).append('\n')
            val orig = o.optString("o")
            if (orig.isNotEmpty()) sb.append("原文：").append(orig).append('\n')
            sb.append("译文：").append(o.optString("z")).append("\n\n")
        }
        val tv = TextView(this)
        tv.text = if (sb.isEmpty()) "还没有记录" else sb.toString()
        tv.setTextIsSelectable(true)
        tv.textSize = 15f
        tv.setPadding(dp(16), dp(12), dp(16), dp(12))
        val sc = ScrollView(this)
        sc.addView(tv)
        AlertDialog.Builder(this)
            .setTitle("翻译历史（最近 100 条）")
            .setView(sc)
            .setPositiveButton("关闭", null)
            .setNeutralButton("清空") { _, _ -> History.clear(this) }
            .show()
    }
}
