package app.budswidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

// Commands from the widget / UI to the live connection.

object Ctl {
    @Volatile var client: SonyClient? = null

    private fun apply(mode: String, level: Int) {
        val c = client
        if (c == null) { LogBuf.add("not connected, command skipped"); return }
        Store.cmdSent()   // UI shows a loading animation until the earbuds answer
        thread(name = "sony-cmd") { c.setAnc(mode, if (level >= 0) level else 20) }
    }

    fun setMode(mode: String) = apply(mode, Store.state.ambLevel)

    fun setLevel(v: Int) {
        val s = Store.state
        if (s.mode != "AMBIENT") { LogBuf.add("level can only be changed in ambient mode"); return }
        apply(s.mode, v)
    }
}

class CmdReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Store.ensureLoaded(c)
        val a = i.action ?: return
        when {
            a == Act.NC -> Ctl.setMode("NC")
            a == Act.AMB -> Ctl.setMode("AMBIENT")
            a == Act.OFF -> Ctl.setMode("OFF")
            a.startsWith(Act.LVL) -> a.removePrefix(Act.LVL).toIntOrNull()?.let { Ctl.setLevel(it * 2) }
        }
    }
}
