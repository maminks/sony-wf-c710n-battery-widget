package app.budswidget

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

// Process-wide state + persistence (the widget shows last known values) and an in-memory log.

object Store {
    @Volatile var state = SonyState()
    private var loaded = false
    val listeners = CopyOnWriteArrayList<(SonyState) -> Unit>()

    @Volatile var appCtx: Context? = null
    @Volatile var pending = false
    private var inflight = 0
    private var gen = 0
    private val ui = Handler(Looper.getMainLooper())

    private fun pushPending() {
        for (l in listeners) l(state)
        appCtx?.let { StatusWidget.refresh(it) }
    }

    fun cmdSent() {
        val g: Int
        synchronized(this) { inflight++; gen++; g = gen; pending = true }
        pushPending()
        ui.postDelayed({
            val clear = synchronized(this) { if (gen == g && pending) { inflight = 0; pending = false; true } else false }
            if (clear) pushPending()   // safety: never spin forever if the earbuds stay silent
        }, 2500)
    }

    fun cmdAnswered() {
        synchronized(this) { if (inflight > 0) inflight--; pending = inflight > 0 }
        pushPending()
    }

    @Synchronized fun ensureLoaded(ctx: Context) {
        appCtx = ctx.applicationContext
        if (loaded) return
        val p = ctx.applicationContext.getSharedPreferences("sony", Context.MODE_PRIVATE)
        state = SonyState(
            connected = false,
            leftLast = p.getInt("l", -1).takeIf { it > 0 } ?: -1,
            rightLast = p.getInt("r", -1).takeIf { it > 0 } ?: -1,
            caseLast = p.getInt("c", -1).takeIf { it > 0 } ?: -1,
            mode = p.getString("mode", "?") ?: "?", ambLevel = p.getInt("amb", -1)
        )
        loaded = true
    }

    fun set(ctx: Context, s: SonyState) {
        ensureLoaded(ctx)
        state = s
        ctx.applicationContext.getSharedPreferences("sony", Context.MODE_PRIVATE).edit()
            .putInt("l", s.leftLast).putInt("r", s.rightLast).putInt("c", s.caseLast)
            .putString("mode", s.mode).putInt("amb", s.ambLevel).apply()
        for (l in listeners) l(s)
        StatusWidget.refresh(ctx.applicationContext)
    }
}

object LogBuf {
    private val sb = StringBuilder()
    @Volatile var sink: ((String) -> Unit)? = null
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized fun add(line: String) {
        val l = fmt.format(Date()) + " " + line + "\n"
        sb.append(l)
        if (sb.length > 40000) sb.delete(0, 15000)
        sink?.invoke(l)
    }

    @Synchronized fun all(): String = sb.toString()
}
