package com.jtliaw.screentranslator

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

/** 受保护的应用：它们在最前面时，不截图、不翻译。 */
object Protect {
    // 包名里的片段（只是猜测，不可能覆盖全世界的银行，所以还有用户自己的名单）
    private val PKG_KEYS = listOf("bank", "wallet", "paypal", "payment", "fintech", "finance", "invest", "broker")
    private val LABEL_KEYS = listOf("bank", "banco", "banque", "wallet", "pay", "银行", "銀行", "钱包", "錢包", "支付", "理财", "理財")

    fun hasUsageAccess(c: Context): Boolean {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** 现在在最前面的应用（没有授权“使用情况访问”时返回 null） */
    @Suppress("DEPRECATION")
    fun foreground(c: Context): String? {
        if (!hasUsageAccess(c)) return null
        val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val ev = usm.queryEvents(now - 24L * 3600 * 1000, now) ?: return null
        val e = UsageEvents.Event()
        var pkg: String? = null
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> pkg = e.packageName
                UsageEvents.Event.MOVE_TO_BACKGROUND -> if (e.packageName == pkg) pkg = null
            }
        }
        return pkg
    }

    fun looksFinancial(c: Context, pkg: String): Boolean {
        val p = pkg.lowercase()
        if (PKG_KEYS.any { p.contains(it) }) return true
        val segs = p.split('.', '_')
        if (segs.any { it.startsWith("pay") || it.endsWith("pay") }) return true
        val label = try {
            c.packageManager.getApplicationInfo(pkg, 0).loadLabel(c.packageManager).toString().lowercase()
        } catch (_: Exception) { "" }
        return LABEL_KEYS.any { label.contains(it) }
    }

    fun isBlocked(c: Context): Boolean {
        val pkg = foreground(c) ?: return false
        if (pkg == c.packageName) return false
        val sp = Cfg.sp(c)
        if (sp.getStringSet("protectedPkgs", emptySet())?.contains(pkg) == true) return true
        return sp.getBoolean("protectAuto", true) && looksFinancial(c, pkg)
    }
}
