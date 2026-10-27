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
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val engines = listOf("Gemini AI（任意语言，需要 API 密钥）", "OCR 离线（仅英文，免费，无需密钥）")
    private val engineKeys = listOf("gemini", "ocr")
    private val resList = listOf("low", "medium", "high", "off")
    private val hideLabels = listOf("3 秒", "5 秒", "8 秒", "15 秒", "不自动消失")
    private val hideVals = listOf("3", "5", "8", "15", "0")

    private lateinit var sEngine: Spinner
    private lateinit var sSource: Spinner
    private lateinit var sTarget: Spinner
    private lateinit var sRes: Spinner
    private lateinit var sHide: Spinner
    private lateinit var fKey: EditText
    private lateinit var fModel: EditText
    private lateinit var fFont: EditText
    private lateinit var cTap: CheckBox
    private lateinit var geminiGroup: LinearLayout
    private lateinit var ocrNote: TextView

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

        fun label(t: String, parent: LinearLayout = root) {
            val v = TextView(this)
            v.text = t; v.textSize = 14f
            v.setTypeface(null, Typeface.BOLD)
            v.setPadding(0, dp(14), 0, dp(2))
            parent.addView(v)
        }
        fun field(value: String, hint: String, parent: LinearLayout = root): EditText {
            val e = EditText(this)
            e.setText(value); e.hint = hint
            e.setSingleLine(true)
            parent.addView(e)
            return e
        }
        fun spinner(items: List<String>, selected: Int, parent: LinearLayout = root): Spinner {
            val s = Spinner(this)
            s.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
            s.setSelection(selected.coerceIn(0, items.size - 1))
            parent.addView(s)
            return s
        }
        fun button(t: String, onClick: () -> Unit) {
            val bt = Button(this)
            bt.text = t
            bt.setOnClickListener { onClick() }
            root.addView(bt)
        }

        val title = TextView(this)
        title.text = "Artocarpus Translate"; title.textSize = 22f
        root.addView(title)

        label("翻译方式")
        val curEngine = engineKeys.indexOf(sp.getString("engine", "gemini"))
        sEngine = spinner(engines, if (curEngine < 0) 0 else curEngine)

        ocrNote = TextView(this)
        ocrNote.text = "OCR 只能识别英文。第一次使用需要联网下载翻译模型（约 30MB），之后完全离线。OCR 不区分简繁体，译成中文时统一为简体。"
        ocrNote.textSize = 13f
        ocrNote.setPadding(0, dp(6), 0, 0)
        root.addView(ocrNote)

        label("译文语言")
        val labels = Langs.all.map { it.label }
        sTarget = spinner(labels, labels.indexOf(sp.getString("target", "简体中文")))

        // Gemini 专用设置
        geminiGroup = LinearLayout(this)
        geminiGroup.orientation = LinearLayout.VERTICAL
        root.addView(geminiGroup)

        label("Gemini API 密钥（每人用自己的，aistudio.google.com/app/apikey 免费申请）", geminiGroup)
        fKey = field(sp.getString("key", "") ?: "", "AIza…", geminiGroup)
        fKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

        label("原文语言", geminiGroup)
        val srcItems = listOf("自动识别") + labels
        sSource = spinner(srcItems, srcItems.indexOf(sp.getString("source", "自动识别")), geminiGroup)

        label("图片精度（low 最省额度）", geminiGroup)
        sRes = spinner(resList, resList.indexOf(sp.getString("mediaRes", "low")), geminiGroup)

        label("模型", geminiGroup)
        fModel = field(sp.getString("model", "gemini-flash-lite-latest") ?: "", "gemini-flash-lite-latest", geminiGroup)

        sEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { updateGroups() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        updateGroups()

        label("译文自动消失时间")
        sHide = spinner(hideLabels, hideVals.indexOf(sp.getString("hideSec", "8")).let { if (it < 0) 2 else it })

        label("译文字号")
        fFont = field(sp.getString("fontSize", "18") ?: "18", "18")
        fFont.inputType = InputType.TYPE_CLASS_NUMBER

        cTap = CheckBox(this)
        cTap.text = "点击蓝色框就重新翻译（这次点击不会传给游戏；关掉则只用长按叶子圆点里的“重译”）"
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

    private fun updateGroups() {
        val gem = sEngine.selectedItemPosition == 0
        geminiGroup.visibility = if (gem) View.VISIBLE else View.GONE
        ocrNote.visibility = if (gem) View.GONE else View.VISIBLE
    }

    /** 悬浮圆点里可以切换 AI / OCR，回到本页时同步一下 */
    override fun onResume() {
        super.onResume()
        val i = engineKeys.indexOf(Cfg.sp(this).getString("engine", "gemini"))
        if (i >= 0 && i != sEngine.selectedItemPosition) sEngine.setSelection(i)
    }

    private fun saveAll() {
        val srcItems = listOf("自动识别") + Langs.all.map { it.label }
        Cfg.sp(this).edit()
            .putString("engine", engineKeys[sEngine.selectedItemPosition])
            .putString("target", Langs.all[sTarget.selectedItemPosition].label)
            .putString("source", srcItems[sSource.selectedItemPosition])
            .putString("mediaRes", resList[sRes.selectedItemPosition])
            .putString("key", fKey.text.toString().trim())
            .putString("model", fModel.text.toString().trim())
            .putString("fontSize", fFont.text.toString().trim())
            .putString("hideSec", hideVals[sHide.selectedItemPosition])
            .putBoolean("tapFrame", cTap.isChecked)
            .apply()
    }

    override fun onPause() {
        saveAll()
        super.onPause()
    }

    private fun startFlow() {
        saveAll()
        if (sEngine.selectedItemPosition == 0 && fKey.text.toString().isBlank()) {
            toast("Gemini 模式需要先填 API 密钥（或改用 OCR）"); return
        }
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
