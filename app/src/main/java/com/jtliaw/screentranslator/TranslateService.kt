package com.jtliaw.screentranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class TranslateService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "translator"
        @Volatile var running = false
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()

    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var held: Image? = null
    private val lock = Any()
    private lateinit var imgThread: HandlerThread
    private lateinit var imgHandler: Handler
    private var capW = 0
    private var capH = 0

    private var pill: View? = null
    private var pillLp: WindowManager.LayoutParams? = null
    private var dotView: View? = null
    private var dotBg: GradientDrawable? = null
    private var active = false
    private var barView: View? = null
    private var engineBtn: TextView? = null
    private val collapseRun = Runnable { collapse() }
    private val hideRun = Runnable { popup?.visibility = View.GONE }
    private var frameView: View? = null
    private var popup: TextView? = null
    private var popupLp: WindowManager.LayoutParams? = null
    private var selector: View? = null

    private var region: Rect? = null
    private var busy = false
    private val cache = LinkedHashMap<String, String>()

    private val cc = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            main.post {
                deactivate()
                ensureDisplay()
                pillLp?.let { it.x = dp(8); it.y = dp(120); pill?.let { v -> safeUpdate(v, it) } }
            }
        }
        override fun onLowMemory() {}
    }

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------ 生命周期
    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        imgThread = HandlerThread("img").also { it.start() }
        imgHandler = Handler(imgThread.looper)
        registerComponentCallbacks(cc)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val intent = intent ?: return START_NOT_STICKY
        if (intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_START && projection == null) {
            startForegroundNotif()
            try {
                val code = intent.getIntExtra(EXTRA_CODE, 0)
                @Suppress("DEPRECATION")
                val data: Intent = intent.getParcelableExtra(EXTRA_DATA) ?: throw Exception(getString(R.string.err_no_projection))
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val p = mpm.getMediaProjection(code, data)
                projection = p
                p.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() { main.post { stopSelf() } }
                }, main)
                setupDisplay()
                showPill()
                running = true
            } catch (e: Exception) {
                toast(getString(R.string.start_failed, e.message ?: ""))
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotif() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Artocarpus Translate", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(
            this, 0, Intent(this, TranslateService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Artocarpus Translate")
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .addAction(android.R.drawable.ic_delete, getString(R.string.exit), stopPi)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacks(collapseRun)
        main.removeCallbacks(hideRun)
        try { unregisterComponentCallbacks(cc) } catch (_: Exception) {}
        rm(pill); rm(frameView); rm(popup); rm(selector)
        pill = null; frameView = null; popup = null; selector = null
        try { vd?.release() } catch (_: Exception) {}
        synchronized(lock) { try { held?.close() } catch (_: Exception) {}; held = null }
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        imgThread.quitSafely()
        exec.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 截屏
    private fun realSize(): IntArray {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(dm)
        return intArrayOf(dm.widthPixels, dm.heightPixels, dm.densityDpi)
    }

    private fun setupDisplay() {
        val p = projection ?: return
        val s = realSize()
        val w = s[0]; val h = s[1]; val d = s[2]
        synchronized(lock) { try { held?.close() } catch (_: Exception) {}; held = null }
        val old = reader
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd ->
            try {
                val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
                synchronized(lock) { try { held?.close() } catch (_: Exception) {}; held = img }
            } catch (_: Exception) {}
        }, imgHandler)
        reader = r
        val v = vd
        if (v == null) {
            vd = p.createVirtualDisplay("st", w, h, d,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
        } else {
            v.resize(w, h, d)
            v.surface = r.surface
        }
        try { old?.close() } catch (_: Exception) {}
        capW = w; capH = h
    }

    private fun ensureDisplay() {
        val s = realSize()
        if (s[0] != capW || s[1] != capH) setupDisplay()
    }

    /** 取最近一帧（画面不动时也有，因为一直留着最新一帧） */
    private fun grabFrame(): Bitmap? {
        synchronized(lock) {
            val img = held ?: return null
            val pl = img.planes[0]
            val buf = pl.buffer
            buf.rewind()
            val bw = pl.rowStride / pl.pixelStride
            val bmp = Bitmap.createBitmap(bw, img.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buf)
            return if (bw != img.width) Bitmap.createBitmap(bmp, 0, 0, img.width, img.height) else bmp
        }
    }

    // ------------------------------------------------------------ 窗口工具
    private fun lp(w: Int, h: Int, touchable: Boolean = true): WindowManager.LayoutParams {
        var f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) f = f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val p = WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            f, PixelFormat.TRANSLUCENT)
        p.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return p
    }

    private fun rm(v: View?) {
        if (v != null) try { wm.removeView(v) } catch (_: Exception) {}
    }

    private fun safeUpdate(v: View, p: WindowManager.LayoutParams) {
        try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------ 悬浮条（平时是一个叶子小圆点）
    private fun engineLabel() = if (Cfg.str(this, "engine", "gemini") == "ocr") "OCR" else "AI"

    private fun showPill() {
        val box = FrameLayout(this)
        val p = lp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        p.x = dp(8); p.y = dp(120)
        pillLp = p

        // 叶子小圆点：白底 = 待机；点一下变红 = 框选/翻译中；再点一下变回白色并关闭；长按 = 更多选项；拖动 = 移动位置
        val dot = ImageView(this)
        dot.setImageResource(R.drawable.artocarpus_icon)
        dot.scaleType = ImageView.ScaleType.FIT_CENTER
        dot.setPadding(dp(6), dp(6), dp(6), dp(6))
        val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xDDFFFFFF.toInt()) }
        dotBg = bg
        dot.background = bg
        dot.alpha = 0.85f
        dot.layoutParams = FrameLayout.LayoutParams(dp(46), dp(46))

        // 长按后出现的小菜单：重译 / AI·OCR / 退出
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.background = GradientDrawable().apply { setColor(0xDD202020.toInt()); cornerRadius = dp(20).toFloat() }
        bar.visibility = View.GONE

        fun tv(t: String): TextView = TextView(this).apply {
            text = t; setTextColor(Color.WHITE); textSize = 15f
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }

        var ox = 0; var oy = 0; var tx = 0f; var ty = 0f
        var moved = false; var longPressed = false
        val lpRun = Runnable { if (!moved) { longPressed = true; expand() } }
        dot.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    ox = p.x; oy = p.y; tx = e.rawX; ty = e.rawY
                    moved = false; longPressed = false
                    main.postDelayed(lpRun, 450)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - tx; val dy = e.rawY - ty
                    if (!moved && (abs(dx) > dp(6) || abs(dy) > dp(6))) { moved = true; main.removeCallbacks(lpRun) }
                    if (moved) { p.x = ox + dx.toInt(); p.y = oy + dy.toInt(); safeUpdate(box, p) }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(lpRun)
                    if (!moved && !longPressed) { if (active) deactivate() else activate() }
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(lpRun)
            }
            true
        }

        val bRe = tv(getString(R.string.retranslate)).apply { setOnClickListener { collapse(); translateRegion() } }
        val bEng = tv(engineLabel()).apply {
            setOnClickListener {
                val ocr = Cfg.str(this@TranslateService, "engine", "gemini") == "ocr"
                Cfg.sp(this@TranslateService).edit().putString("engine", if (ocr) "gemini" else "ocr").apply()
                text = engineLabel()
                toast(getString(if (ocr) R.string.switched_gemini else R.string.switched_ocr))
                scheduleCollapse()
            }
        }
        val bX = tv(getString(R.string.exit)).apply { setOnClickListener { stopSelf() } }
        engineBtn = bEng
        bar.addView(bRe); bar.addView(bEng); bar.addView(bX)

        box.addView(dot); box.addView(bar)
        dotView = dot; barView = bar
        pill = box
        wm.addView(box, p)
    }

    private fun setDotActive(on: Boolean) {
        dotBg?.setColor(if (on) 0xFFE53935.toInt() else 0xDDFFFFFF.toInt())
        dotView?.alpha = if (on) 1f else 0.85f
    }

    /** 点圆点（白→红）：开始框选 */
    private fun activate() {
        if (busy) return
        exec.execute {
            val blocked = try { Protect.isBlocked(this) } catch (_: Exception) { false }
            main.post {
                if (blocked) {
                    toast(getString(R.string.protected_app))
                } else if (!active && !busy) {
                    active = true
                    setDotActive(true)
                    collapse()
                    showSelector()
                }
            }
        }
    }

    /** 再点圆点（红→白）：关掉框选、蓝框和译文 */
    private fun deactivate() {
        active = false
        setDotActive(false)
        rm(selector); selector = null
        clearRegion()
        main.removeCallbacks(hideRun)
    }

    private fun expand() {
        val box = pill ?: return
        val p = pillLp ?: return
        engineBtn?.text = engineLabel()
        dotView?.visibility = View.GONE
        barView?.visibility = View.VISIBLE
        box.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        p.x = p.x.coerceIn(0, max(0, capW - box.measuredWidth))
        safeUpdate(box, p)
        scheduleCollapse()
    }

    private fun collapse() {
        main.removeCallbacks(collapseRun)
        barView?.visibility = View.GONE
        dotView?.visibility = View.VISIBLE
    }

    private fun scheduleCollapse() {
        main.removeCallbacks(collapseRun)
        main.postDelayed(collapseRun, 5000)
    }

    // ------------------------------------------------------------ 框选
    private inner class SelView(ctx: Context) : View(ctx) {
        var sx = 0f; var sy = 0f; var ex = 0f; var ey = 0f
        var dragging = false
        private val dim = Paint().apply { color = 0x66000000 }
        private val border = Paint().apply {
            color = 0xFF2F9BFF.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat()
        }
        private val txt = Paint().apply {
            color = Color.WHITE; textSize = dp(16).toFloat(); textAlign = Paint.Align.CENTER; isAntiAlias = true
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (!dragging) {
                c.drawRect(0f, 0f, w, h, dim)
                c.drawText(getString(R.string.select_hint), w / 2, h / 2, txt)
                return
            }
            val l = min(sx, ex); val r = max(sx, ex); val t = min(sy, ey); val b = max(sy, ey)
            c.drawRect(0f, 0f, w, t, dim); c.drawRect(0f, b, w, h, dim)
            c.drawRect(0f, t, l, b, dim); c.drawRect(r, t, w, b, dim)
            c.drawRect(l, t, r, b, border)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.x; sy = e.y; ex = sx; ey = sy; dragging = true; invalidate() }
                MotionEvent.ACTION_MOVE -> { ex = e.x; ey = e.y; invalidate() }
                MotionEvent.ACTION_UP -> {
                    ex = e.x; ey = e.y
                    val loc = IntArray(2); getLocationOnScreen(loc)
                    val l = (min(sx, ex) + loc[0]).toInt().coerceIn(0, capW)
                    val r = (max(sx, ex) + loc[0]).toInt().coerceIn(0, capW)
                    val t = (min(sy, ey) + loc[1]).toInt().coerceIn(0, capH)
                    val b = (max(sy, ey) + loc[1]).toInt().coerceIn(0, capH)
                    finishSelect(if (r - l >= dp(24) && b - t >= dp(16)) Rect(l, t, r, b) else null)
                }
                MotionEvent.ACTION_CANCEL -> finishSelect(null)
            }
            return true
        }
    }

    private fun showSelector() {
        if (selector != null || busy) return
        ensureDisplay()
        popup?.visibility = View.GONE
        rm(frameView); frameView = null
        val v = SelView(this)
        selector = v
        wm.addView(v, lp(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT))
        // 把叶子圆点重新放到最上层，框选时也能点它来关闭
        pill?.let { pv -> try { wm.removeView(pv); wm.addView(pv, pillLp) } catch (_: Exception) {} }
    }

    private fun finishSelect(r: Rect?) {
        rm(selector); selector = null
        if (r != null) region = r
        if (region != null) {
            showFrame()
            if (r != null) translateRegion()
        } else {
            deactivate()
        }
    }

    private fun clearRegion() {
        region = null
        rm(frameView); frameView = null
        popup?.visibility = View.GONE
    }

    private fun showFrame() {
        rm(frameView); frameView = null
        val r = region ?: return
        val tap = Cfg.sp(this).getBoolean("tapFrame", true)
        val v = View(this)
        v.background = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(2), 0xFF2F9BFF.toInt()) }
        val p = lp(r.width(), r.height(), tap)
        p.x = r.left; p.y = r.top
        if (tap) v.setOnClickListener { translateRegion() }
        frameView = v
        wm.addView(v, p)
    }

    // ------------------------------------------------------------ 翻译
    private fun translateRegion() {
        val r = region ?: run { toast(getString(R.string.toast_select_first)); return }
        if (busy) return
        val s = realSize()
        if (s[0] != capW || s[1] != capH) {
            deactivate(); ensureDisplay(); toast(getString(R.string.toast_rotated)); return
        }
        busy = true
        pill?.visibility = View.INVISIBLE
        frameView?.visibility = View.INVISIBLE
        popup?.visibility = View.INVISIBLE
        val copy = Rect(r)
        main.postDelayed({ exec.execute { work(copy) } }, 250)
    }

    private fun restoreOverlays() {
        pill?.visibility = View.VISIBLE
        frameView?.visibility = View.VISIBLE
    }

    private fun work(r: Rect) {
        try {
            // 受保护的应用在最前面：不截图、不翻译
            if (Protect.isBlocked(this)) {
                main.post { restoreOverlays(); showPopup(getString(R.string.protected_app)) }
                return
            }
            val full = grabFrame()
            main.post { restoreOverlays(); if (full != null) showPopup(getString(R.string.translating), false) }
            if (full == null) throw Exception(getString(R.string.err_no_frame))

            val inset = 6
            val x0 = (r.left + inset).coerceIn(0, full.width - 1)
            val y0 = (r.top + inset).coerceIn(0, full.height - 1)
            val x1 = (r.right - inset).coerceIn(x0 + 1, full.width)
            val y1 = (r.bottom - inset).coerceIn(y0 + 1, full.height)
            var crop = Bitmap.createBitmap(full, x0, y0, x1 - x0, y1 - y0)
            val m = max(crop.width, crop.height)
            if (m > 1600) {
                val k = 1600f / m
                crop = Bitmap.createScaledBitmap(crop,
                    (crop.width * k).toInt().coerceAtLeast(1), (crop.height * k).toInt().coerceAtLeast(1), true)
            }

            val sp = Cfg.sp(this)
            val engine = sp.getString("engine", "gemini") ?: "gemini"
            val target = Langs.find(sp.getString("target", null) ?: Langs.defaultLabel(this))
            val h: String
            val res: TranslateResult
            if (engine == "ocr") {
                h = hashOf(crop, "ocr" + target.label)
                val c = synchronized(cache) { cache[h] }
                if (c != null) { main.post { showPopup(c) }; return }
                res = Ocr.run(this, crop, target.code)
            } else {
                val key = sp.getString("key", "")?.trim() ?: ""
                if (key.isEmpty()) throw Exception(getString(R.string.err_no_key))
                val model = (sp.getString("model", "") ?: "").trim().ifEmpty { "gemini-flash-lite-latest" }
                val srcLabel = sp.getString("source", "自动识别") ?: "自动识别"
                val source = if (srcLabel == "自动识别") srcLabel else Langs.find(srcLabel).gem
                val mediaRes = sp.getString("mediaRes", "low") ?: "low"
                val system = Gemini.buildSystem(source, target.gem)
                h = hashOf(crop, system + model + mediaRes)
                val c = synchronized(cache) { cache[h] }
                if (c != null) { main.post { showPopup(c) }; return }
                val bos = ByteArrayOutputStream()
                crop.compress(Bitmap.CompressFormat.JPEG, 90, bos)
                res = Gemini.translate(this, key, model, system, bos.toByteArray(), mediaRes, target.gem)
            }
            if (res.translation == Gemini.NO_TEXT) {
                main.post { showPopup(getString(R.string.no_text)) }
                return
            }
            if (res.ok) {
                synchronized(cache) {
                    if (cache.size >= 300) cache.remove(cache.keys.first())
                    cache[h] = res.translation
                }
                History.add(this, res.original, res.translation)
                main.post { showPopup(res.translation) }
            } else {
                main.post { showPopup(getString(R.string.maybe_failed) + "\n" + res.translation) }
            }
        } catch (e: Exception) {
            main.post { showPopup(getString(R.string.error_fmt, e.message ?: e.javaClass.simpleName)) }
        } finally {
            main.post { restoreOverlays(); busy = false }
        }
    }

    private fun hashOf(b: Bitmap, extra: String): String {
        val s = Bitmap.createScaledBitmap(b, 48, 48, true)
        val px = IntArray(48 * 48)
        s.getPixels(px, 0, 48, 0, 0, 48, 48)
        val bytes = ByteArray(px.size) { i ->
            val c = px[i]
            ((((c shr 16) and 0xff) + ((c shr 8) and 0xff) + (c and 0xff)) / 3 / 16).toByte()
        }
        val md = MessageDigest.getInstance("MD5")
        md.update(bytes); md.update(extra.toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------ 译文浮窗
    private fun showPopup(text: String, autoHide: Boolean = true) {
        if (!active) return
        val sw = capW; val sh = capH
        if (sw == 0 || sh == 0) return
        val fs = (Cfg.sp(this).getString("fontSize", "18") ?: "18").toIntOrNull()?.coerceIn(10, 48) ?: 18
        var tv = popup
        if (tv == null) {
            tv = TextView(this)
            tv.setTextColor(Color.WHITE)
            tv.setPadding(dp(12), dp(8), dp(12), dp(8))
            tv.background = GradientDrawable().apply { setColor(0xEE101010.toInt()); cornerRadius = dp(10).toFloat() }
            tv.setOnClickListener { it.visibility = View.GONE }
            popup = tv
            val p = lp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
            popupLp = p
            wm.addView(tv, p)
        }
        val p = popupLp ?: return
        tv.text = text
        tv.textSize = fs.toFloat()
        tv.visibility = View.VISIBLE

        val r = region
        val margin = dp(8)
        val pw = (if (r != null) max(r.width(), (sw * 0.6).toInt()) else (sw * 0.8).toInt()).coerceAtMost(sw - 2 * margin)
        tv.measure(View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val ph = min(tv.measuredHeight, (sh * 0.5).toInt())
        val x = (r?.left ?: margin).coerceIn(margin, max(margin, sw - pw - margin))
        val y = if (r == null) margin
        else if (r.bottom + margin + ph <= sh - margin) r.bottom + margin
        else max(margin, r.top - margin - ph)
        p.x = x; p.y = y; p.width = pw; p.height = ph
        safeUpdate(tv, p)

        main.removeCallbacks(hideRun)
        if (autoHide) {
            val sec = (Cfg.sp(this).getString("hideSec", "8") ?: "8").toIntOrNull() ?: 8
            if (sec > 0) main.postDelayed(hideRun, sec * 1000L)
        }
    }
}
