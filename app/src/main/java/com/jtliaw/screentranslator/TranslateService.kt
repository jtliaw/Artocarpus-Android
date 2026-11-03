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
import android.graphics.RectF
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
import kotlin.math.hypot
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
                    if (!moved && !longPressed) {
                        when {
                            selector != null -> confirmSelection()   // 框选/调整中再点：确认这个框并翻译
                            active -> translateRegion()              // 红色：重新翻译已框好的区域（不再显示蓝框）
                            else -> activate()                       // 白色：开始框选
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(lpRun)
            }
            true
        }

        val bRe = tv(getString(R.string.bar_reselect)).apply {
            setOnClickListener { collapse(); if (active) showSelector() else activate() }
        }
        val bClose = tv(getString(R.string.bar_close)).apply { setOnClickListener { collapse(); deactivate() } }
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
        bar.addView(bRe); bar.addView(bClose); bar.addView(bEng); bar.addView(bX)

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
    /**
     * 框选层：先拖出一个框；之后可以在框里拖动来移动，拖边或角来调整大小；
     * 调好后点一下红色叶子才翻译。靠近屏幕边缘时会自动贴边，手指够不到的地方也能框到。
     */
    private inner class SelView(ctx: Context) : View(ctx) {
        private var box: RectF? = null
        private var prevBox: RectF? = null
        private var pending: Rect? = null
        private var startBox: RectF? = null
        private var mode = 0          // 0 无，1 新画，2 移动，3 拉伸
        private var mask = 0          // 1 左，2 上，4 右，8 下
        private var downX = 0f
        private var downY = 0f
        private val slop = dp(24).toFloat()
        private val snap = dp(32).toFloat()
        private val minW = dp(28).toFloat()
        private val minH = dp(20).toFloat()
        private val dim = Paint().apply { color = 0x66000000 }
        private val border = Paint().apply {
            color = 0xFF2F9BFF.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat()
        }
        private val hFill = Paint().apply { color = 0xFF2F9BFF.toInt(); isAntiAlias = true }
        private val hRing = Paint().apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat(); isAntiAlias = true
        }
        private val txt = Paint().apply {
            color = Color.WHITE; textSize = dp(16).toFloat(); textAlign = Paint.Align.CENTER; isAntiAlias = true
            setShadowLayer(dp(3).toFloat(), 0f, 0f, Color.BLACK)
        }

        /** 从已有的区域开始调整（长按菜单里的“重新框选”） */
        fun setInitial(r: Rect) { pending = Rect(r) }

        /** 当前的框（屏幕坐标）；没有框或太小则返回 null */
        fun currentRect(): Rect? {
            val b = box ?: return null
            if (b.width() < minW || b.height() < minH) return null
            val loc = IntArray(2); getLocationOnScreen(loc)
            return Rect(
                (b.left + loc[0]).toInt().coerceIn(0, capW), (b.top + loc[1]).toInt().coerceIn(0, capH),
                (b.right + loc[0]).toInt().coerceIn(0, capW), (b.bottom + loc[1]).toInt().coerceIn(0, capH))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, bt: Int) {
            super.onLayout(changed, l, t, r, bt)
            val p = pending
            if (p != null && box == null) {
                val loc = IntArray(2); getLocationOnScreen(loc)
                box = RectF((p.left - loc[0]).toFloat(), (p.top - loc[1]).toFloat(),
                    (p.right - loc[0]).toFloat(), (p.bottom - loc[1]).toFloat())
                pending = null
                invalidate()
            }
        }

        private fun drawLines(c: Canvas, text: String, cx: Float, cy: Float) {
            var y = cy
            for (ln in text.split("\n")) { c.drawText(ln, cx, y, txt); y += txt.textSize * 1.35f }
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val b = box
            if (b == null) {
                c.drawRect(0f, 0f, w, h, dim)
                drawLines(c, getString(R.string.select_hint), w / 2, h / 2)
                return
            }
            c.drawRect(0f, 0f, w, b.top, dim); c.drawRect(0f, b.bottom, w, h, dim)
            c.drawRect(0f, b.top, b.left, b.bottom, dim); c.drawRect(b.right, b.top, w, b.bottom, dim)
            c.drawRect(b, border)
            val mx = (b.left + b.right) / 2; val my = (b.top + b.bottom) / 2
            val pts = floatArrayOf(b.left, b.top, mx, b.top, b.right, b.top, b.right, my,
                b.right, b.bottom, mx, b.bottom, b.left, b.bottom, b.left, my)
            var i = 0
            while (i < pts.size) {
                c.drawCircle(pts[i], pts[i + 1], dp(7).toFloat(), hFill)
                c.drawCircle(pts[i], pts[i + 1], dp(7).toFloat(), hRing)
                i += 2
            }
            if (mode == 0) drawLines(c, getString(R.string.select_hint_edit), w / 2, dp(110).toFloat())
        }

        private fun hit(b: RectF, x: Float, y: Float): Int {
            val inX = x >= b.left - slop && x <= b.right + slop
            val inY = y >= b.top - slop && y <= b.bottom + slop
            var m = 0
            if (inY && abs(x - b.left) <= slop && (abs(x - b.right) > slop || abs(x - b.left) <= abs(x - b.right))) m = m or 1
            if (inX && abs(y - b.top) <= slop && (abs(y - b.bottom) > slop || abs(y - b.top) <= abs(y - b.bottom))) m = m or 2
            if (inY && abs(x - b.right) <= slop && (m and 1) == 0) m = m or 4
            if (inX && abs(y - b.bottom) <= slop && (m and 2) == 0) m = m or 8
            if (m != 0) return m
            return if (b.contains(x, y)) 16 else 0
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val x = e.x; val y = e.y
            val w = width.toFloat(); val h = height.toFloat()
            try {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = x; downY = y
                        val b = box
                        val m = if (b != null) hit(b, x, y) else 0
                        if (b != null && m == 16) { mode = 2; startBox = RectF(b) }
                        else if (b != null && m != 0) { mode = 3; mask = m; startBox = RectF(b) }
                        else { mode = 1; prevBox = b?.let { RectF(it) }; box = null }
                        invalidate()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = x - downX; val dy = y - downY
                        when (mode) {
                            1 -> {
                                var l = min(downX, x).coerceIn(0f, w); var t = min(downY, y).coerceIn(0f, h)
                                var r = max(downX, x).coerceIn(0f, w); var bt = max(downY, y).coerceIn(0f, h)
                                if (l < snap) l = 0f
                                if (t < snap) t = 0f
                                if (w - r < snap) r = w
                                if (h - bt < snap) bt = h
                                box = RectF(l, t, r, bt)
                            }
                            2 -> {
                                val s = startBox!!
                                var nl = (s.left + dx).coerceIn(0f, w - s.width())
                                var nt = (s.top + dy).coerceIn(0f, h - s.height())
                                if (nl < snap) nl = 0f
                                if (w - (nl + s.width()) < snap) nl = w - s.width()
                                if (nt < snap) nt = 0f
                                if (h - (nt + s.height()) < snap) nt = h - s.height()
                                box = RectF(nl, nt, nl + s.width(), nt + s.height())
                            }
                            3 -> {
                                val s = startBox!!
                                var l = s.left; var t = s.top; var r = s.right; var bt = s.bottom
                                if ((mask and 1) != 0) { l = (s.left + dx).coerceIn(0f, s.right - minW); if (l < snap) l = 0f }
                                if ((mask and 4) != 0) { r = (s.right + dx).coerceIn(s.left + minW, w); if (w - r < snap) r = w }
                                if ((mask and 2) != 0) { t = (s.top + dy).coerceIn(0f, s.bottom - minH); if (t < snap) t = 0f }
                                if ((mask and 8) != 0) { bt = (s.bottom + dy).coerceIn(s.top + minH, h); if (h - bt < snap) bt = h }
                                box = RectF(l, t, r, bt)
                            }
                        }
                        invalidate()
                    }
                    MotionEvent.ACTION_UP -> {
                        if (mode == 1) {
                            val moved = hypot(x - downX, y - downY) > dp(8)
                            val cur = box
                            val valid = moved && cur != null && cur.width() >= minW && cur.height() >= minH
                            if (!valid) {
                                box = prevBox
                                if (box == null && !moved) { mode = 0; finishSelect(null); return true }
                            } else if (Cfg.sp(this@TranslateService).getBoolean("instant", false)) {
                                val r = currentRect(); mode = 0; finishSelect(r); return true
                            }
                        }
                        mode = 0
                        invalidate()
                    }
                    MotionEvent.ACTION_CANCEL -> { mode = 0; invalidate() }
                }
            } catch (_: Exception) {
                mode = 0
            }
            return true
        }
    }

    /** 点红色叶子：确认现在的框并翻译；还没有框就当作取消 */
    private fun confirmSelection() {
        val sv = selector as? SelView
        finishSelect(sv?.currentRect())
    }

    private fun showSelector() {
        if (selector != null || busy) return
        ensureDisplay()
        popup?.visibility = View.GONE
        rm(frameView); frameView = null
        val v = SelView(this)
        selector = v
        // 重新框选：从空白开始画新框；中途取消则保留原来的区域
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
        // 不再画蓝框：框好的区域只在内部记住，屏幕上什么都不显示
        rm(frameView); frameView = null
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
