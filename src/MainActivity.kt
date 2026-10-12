package app.budswidget

import android.Manifest
import android.animation.LayoutTransition
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

// Main screen: status, mode switch, ambient level slider, start/stop, collapsible log.

class MainActivity : Activity() {
    private lateinit var title: TextView
    private lateinit var sub: TextView
    private lateinit var bl: TextView
    private lateinit var br: TextView
    private lateinit var bcase: TextView
    private lateinit var lvlText: TextView
    private lateinit var seek: SeekBar
    private lateinit var logView: TextView
    private lateinit var logHead: TextView
    private lateinit var scroll: ScrollView
    private val modeBtns = LinkedHashMap<String, TextView>()
    private val modeColors = mapOf("NC" to Col.BLUE, "AMBIENT" to Col.GREEN, "OFF" to Col.GRAY)
    private var touching = false
    private var logOpen = true
    private val pulses = ArrayList<ObjectAnimator>()

    private fun setPulse(on: Boolean) {
        if (on && pulses.isEmpty()) {
            for (v in listOf<View>(title, lvlText)) {
                pulses.add(ObjectAnimator.ofFloat(v, "alpha", 1f, 0.3f).apply {
                    duration = 450; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
                })
            }
        } else if (!on && pulses.isNotEmpty()) {
            for (a in pulses) a.cancel()
            pulses.clear()
            title.alpha = 1f; lvlText.alpha = 1f
        }
    }

    private val listener: (SonyState) -> Unit = { s -> runOnUiThread { showState(s) } }

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()

    private fun bg(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    private fun label(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    // card; weight > 0 makes it share the free vertical space (so it grows when the log is collapsed)
    private fun card(weight: Float, minDp: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = bg(Col.CARD, 22)
        setPadding(dp(18), dp(14), dp(18), dp(14))
        minimumHeight = dp(minDp)
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(-1, if (weight > 0f) 0 else -2, weight).apply { bottomMargin = dp(10) }
    }

    private fun pill(t: String, color: Int, f: () -> Unit) = TextView(this).apply {
        text = t; textSize = 14f; setTextColor(Col.WHITE); gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD; maxLines = 1
        background = bg(color, 16)
        setOnClickListener { bounce(it); f() }
    }

    private fun chip(name: String): Pair<LinearLayout, TextView> {
        val v = label("—", 18f, Col.WHITE, true).apply { gravity = Gravity.CENTER; maxLines = 1 }
        val n = label(name, 11f, Col.MUTED).apply { gravity = Gravity.CENTER }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = bg(Col.CHIP, 16)
            setPadding(dp(4), dp(8), dp(4), dp(8))
            addView(v); addView(n)
        }
        return box to v
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.ensureLoaded(this)
        logOpen = getSharedPreferences("sony", Context.MODE_PRIVATE).getBoolean("logOpen", true)
        window.statusBarColor = Col.BG
        window.navigationBarColor = Col.BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Col.BG)
            setPadding(dp(14), dp(22), dp(14), dp(12))
            layoutTransition = LayoutTransition().apply { enableTransitionType(LayoutTransition.CHANGING) }
        }

        // header: device + mode + battery (chips stretch with the free space)
        val hdr = card(1.2f, 140)
        sub = label("WF-C710N", 12f, Col.MUTED)
        title = label("—", 26f, Col.WHITE, true)
        hdr.addView(sub)
        hdr.addView(title)
        val (cl, vl) = chip("L"); bl = vl
        val (cr, vr) = chip("R"); br = vr
        val (cc, vc) = chip(Str.get("bat_case")); bcase = vc
        val batRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (x in listOf(cl, cr, cc)) batRow.addView(x, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        hdr.addView(batRow, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })
        root.addView(hdr)

        // mode: one segmented control
        val modes = card(1f, 100)
        modes.addView(label(Str.get("label_mode"), 12f, Col.MUTED).apply { setPadding(0, 0, 0, dp(8)) })
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bg(0xFF12161B.toInt(), 18)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        for ((key, name, w) in listOf(Triple("NC", Str.get("title_nc"), 1.2f), Triple("AMBIENT", Str.get("title_amb"), 1.3f), Triple("OFF", Str.get("seg_off"), 0.7f))) {
            val t = TextView(this).apply {
                text = name; gravity = Gravity.CENTER; setTextColor(Col.TEXT2)
                typeface = Typeface.DEFAULT_BOLD; maxLines = 1
                setPadding(dp(4), 0, dp(4), 0)
                setAutoSizeTextTypeUniformWithConfiguration(9, 14, 1, TypedValue.COMPLEX_UNIT_SP)
                setOnClickListener { bounce(it); Ctl.setMode(key) }
            }
            modeBtns[key] = t
            track.addView(t, LinearLayout.LayoutParams(0, -1, w))
        }
        modes.addView(track, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(modes)

        // ambient level
        val lv = card(0.8f, 90)
        val lvHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        lvHead.addView(label(Str.get("label_level"), 12f, Col.MUTED), LinearLayout.LayoutParams(0, -2, 1f))
        lvlText = label("—", 15f, Col.WHITE, true)
        lvHead.addView(lvlText)
        lv.addView(lvHead)
        seek = SeekBar(this).apply {
            max = 20
            progressTintList = ColorStateList.valueOf(Col.GREEN)
            thumbTintList = ColorStateList.valueOf(Col.GREEN)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) lvlText.text = "$p / 20"
                }
                override fun onStartTrackingTouch(sb: SeekBar) { touching = true }
                override fun onStopTrackingTouch(sb: SeekBar) { touching = false; Ctl.setLevel(sb.progress) }
            })
        }
        lv.addView(seek, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        root.addView(lv)

        // service buttons: two equal full-width buttons
        val ctl = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(6) }
        }
        ctl.addView(pill(Str.get("btn_start"), Col.BLUE) { startSvc() }, LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = dp(5) })
        ctl.addView(pill(Str.get("btn_stop"), Col.BTN) { stopService(Intent(this, SonyService::class.java)) },
            LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(5) })
        root.addView(ctl)

        // log (collapsible; when hidden the cards above take its space)
        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextColor(Col.TEXT2); setTextIsSelectable(true) }
        scroll = ScrollView(this).apply {
            background = bg(Col.CARD, 16)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            addView(logView)
        }
        logHead = label("", 12f, Col.MUTED).apply {
            setPadding(dp(6), dp(8), dp(6), dp(8))
            setOnClickListener { setLog(!logOpen) }
        }
        root.addView(logHead)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 3.0f))
        setLog(logOpen)
        setContentView(root)
    }

    private fun bounce(v: View) {
        v.animate().cancel()
        v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(70).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }.start()
    }

    private fun setLog(open: Boolean) {
        logOpen = open
        scroll.visibility = if (open) View.VISIBLE else View.GONE
        logHead.text = Str.get("log_title") + if (open) "  ▾" else "  ▸"
        getSharedPreferences("sony", Context.MODE_PRIVATE).edit().putBoolean("logOpen", open).apply()
    }

    override fun onResume() {
        super.onResume()
        Store.listeners.add(listener)
        logView.text = LogBuf.all()
        LogBuf.sink = { l -> runOnUiThread { logView.append(l); scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) } } }
        showState(Store.state)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onPause() {
        Store.listeners.remove(listener)
        LogBuf.sink = null
        setPulse(false)
        super.onPause()
    }

    private fun showState(s: SonyState) {
        val on = s.connected
        title.text = Fmt.widgetTitle(s)
        title.setTextColor(if (on) Col.WHITE else Col.MUTED)
        sub.text = "WF-C710N  ·  " + Str.get(if (on) "status_on" else "status_off")
        sub.setTextColor(if (on) Col.GREEN else Col.MUTED)

        fun setBud(tv: TextView, n: Int, chg: Boolean, last: Int) {
            tv.text = Fmt.bud(n, chg, last, on)
            tv.setTextColor(if (on && n >= 0) (if (n <= 15) Col.RED else Col.WHITE) else Col.MUTED)
        }
        setBud(bl, s.left, s.leftChg, s.leftLast)
        setBud(br, s.right, s.rightChg, s.rightLast)
        setBud(bcase, s.caseBat, s.caseChg, s.caseLast)

        for ((k, tv) in modeBtns) {
            val active = on && s.mode == k
            tv.background = if (active) bg(modeColors[k] ?: Col.BTN, 14) else null
            tv.setTextColor(if (active) Col.WHITE else if (on) Col.TEXT2 else Col.MUTED)
        }
        if (!touching && !Store.pending) {
            lvlText.text = if (s.ambLevel >= 0) "${s.ambLevel} / 20" else "—"
            seek.setProgress(if (s.ambLevel >= 0) s.ambLevel else 0, true)
        }
        val amb = on && s.mode == "AMBIENT"
        seek.isEnabled = amb
        lvlText.setTextColor(if (amb) Col.WHITE else Col.MUTED)
        setPulse(Store.pending)
    }

    private fun startSvc() {
        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) { requestPermissions(need.toTypedArray(), 1); return }
        launchService()
    }

    private fun launchService() {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) { LogBuf.add("BLUETOOTH_CONNECT denied"); return }
        startForegroundService(Intent(this, SonyService::class.java))
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        launchService()
    }
}
