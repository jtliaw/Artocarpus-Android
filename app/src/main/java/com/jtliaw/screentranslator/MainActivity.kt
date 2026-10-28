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

    private val AUTO_KEY = "自动识别"   // 存档用的固定键，界面显示走字符串资源
    private val engineKeys = listOf("gemini", "ocr")
    private val resList = listOf("low", "medium", "high", "off")
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
    private lateinit var cProtectAuto: CheckBox
    private lateinit var usageStatus: TextView
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
        fun note(t: String): TextView {
            val v = TextView(this)
            v.text = t; v.textSize = 13f
            v.setPadding(0, dp(6), 0, 0)
            root.addView(v)
            return v
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

        label(getString(R.string.lbl_engine))
        val curEngine = engineKeys.indexOf(sp.getString("engine", "gemini"))
        sEngine = spinner(listOf(getString(R.string.engine_gemini), getString(R.string.engine_ocr)),
            if (curEngine < 0) 0 else curEngine)

        ocrNote = note(getString(R.string.ocr_note))

        label(getString(R.string.lbl_target))
        val langNames = Langs.all.map { getString(it.nameRes) }
        val tIdx = Langs.all.indexOfFirst { it.label == sp.getString("target", Langs.defaultLabel(this)) }
        sTarget = spinner(langNames, if (tIdx < 0) 0 else tIdx)

        // Gemini 专用设置
        geminiGroup = LinearLayout(this)
        geminiGroup.orientation = LinearLayout.VERTICAL
        root.addView(geminiGroup)

        label(getString(R.string.lbl_api_key), geminiGroup)
        fKey = field(sp.getString("key", "") ?: "", "AIza…", geminiGroup)
        fKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

        label(getString(R.string.lbl_source), geminiGroup)
        val srcNames = listOf(getString(R.string.auto_detect)) + langNames
        val savedSrc = sp.getString("source", AUTO_KEY)
        val sIdx = if (savedSrc == AUTO_KEY) 0 else Langs.all.indexOfFirst { it.label == savedSrc } + 1
        sSource = spinner(srcNames, sIdx, geminiGroup)

        label(getString(R.string.lbl_res), geminiGroup)
        sRes = spinner(resList, resList.indexOf(sp.getString("mediaRes", "low")), geminiGroup)

        label(getString(R.string.lbl_model), geminiGroup)
        fModel = field(sp.getString("model", "gemini-flash-lite-latest") ?: "", "gemini-flash-lite-latest", geminiGroup)

        sEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { updateGroups() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        updateGroups()

        label(getString(R.string.lbl_hide))
        val hideLabels = hideVals.map {
            if (it == "0") getString(R.string.hide_never) else getString(R.string.seconds_fmt, it.toInt())
        }
        sHide = spinner(hideLabels, hideVals.indexOf(sp.getString("hideSec", "8")).let { if (it < 0) 2 else it })

        label(getString(R.string.lbl_font))
        fFont = field(sp.getString("fontSize", "18") ?: "18", "18")
        fFont.inputType = InputType.TYPE_CLASS_NUMBER

        cTap = CheckBox(this)
        cTap.text = getString(R.string.chk_tap)
        cTap.isChecked = sp.getBoolean("tapFrame", true)
        root.addView(cTap)

        label(getString(R.string.lbl_start))
        button(getString(R.string.btn_start)) { startFlow() }
        button(getString(R.string.btn_stop)) {
            if (TranslateService.running) {
                startService(Intent(this, TranslateService::class.java).setAction(TranslateService.ACTION_STOP))
            } else toast(getString(R.string.not_running))
        }
        button(getString(R.string.btn_history)) { showHistory() }

        label(getString(R.string.protect_title))
        note(getString(R.string.protect_note))
        cProtectAuto = CheckBox(this)
        cProtectAuto.text = getString(R.string.chk_protect_auto)
        cProtectAuto.isChecked = sp.getBoolean("protectAuto", true)
        root.addView(cProtectAuto)
        button(getString(R.string.btn_choose_apps)) { chooseApps() }
        usageStatus = note("")
        button(getString(R.string.btn_usage)) { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }

        label(getString(R.string.lbl_bg))
        note(getString(R.string.bg_note))
        button(getString(R.string.btn_app_settings)) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        button(getString(R.string.btn_battery)) {
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
        usageStatus.text = getString(if (Protect.hasUsageAccess(this)) R.string.usage_on else R.string.usage_off)
    }

    private fun chooseApps() {
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(q, 0)
            .map { Pair(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.first }
            .filter { it.first != packageName }
            .sortedBy { it.second.lowercase() }
        val saved = HashSet(Cfg.sp(this).getStringSet("protectedPkgs", emptySet()) ?: emptySet())
        val checked = BooleanArray(apps.size) { saved.contains(apps[it].first) }
        val auto = cProtectAuto.isChecked
        val names = apps.map { (pkg, label) ->
            if (auto && Protect.looksFinancial(this, pkg)) "🔒 $label" else label
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.dlg_choose_title)
            .setMultiChoiceItems(names, checked) { _, i, on ->
                if (on) saved.add(apps[i].first) else saved.remove(apps[i].first)
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Cfg.sp(this).edit().putStringSet("protectedPkgs", saved).apply()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveAll() {
        val srcPos = sSource.selectedItemPosition
        Cfg.sp(this).edit()
            .putString("engine", engineKeys[sEngine.selectedItemPosition])
            .putBoolean("protectAuto", cProtectAuto.isChecked)
            .putString("target", Langs.all[sTarget.selectedItemPosition].label)
            .putString("source", if (srcPos <= 0) AUTO_KEY else Langs.all[srcPos - 1].label)
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
            toast(getString(R.string.toast_need_key)); return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast(getString(R.string.toast_need_overlay))
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
                toast(getString(R.string.toast_no_capture))
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
            if (orig.isNotEmpty()) sb.append(getString(R.string.original)).append(orig).append('\n')
            sb.append(getString(R.string.translation)).append(o.optString("z")).append("\n\n")
        }
        val tv = TextView(this)
        tv.text = if (sb.isEmpty()) getString(R.string.history_empty) else sb.toString()
        tv.setTextIsSelectable(true)
        tv.textSize = 15f
        tv.setPadding(dp(16), dp(12), dp(16), dp(12))
        val sc = ScrollView(this)
        sc.addView(tv)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.history_title))
            .setView(sc)
            .setPositiveButton(getString(R.string.close), null)
            .setNeutralButton(getString(R.string.clear)) { _, _ -> History.clear(this) }
            .show()
    }
}
