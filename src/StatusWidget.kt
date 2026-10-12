package app.budswidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

// Home-screen widget (classic RemoteViews, no AndroidX).

class StatusWidget : AppWidgetProvider() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_LOCALE_CHANGED) { Store.ensureLoaded(ctx); refresh(ctx) }
        else super.onReceive(ctx, intent)
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        Store.ensureLoaded(ctx)
        mgr.updateAppWidget(ids, build(ctx, Store.state))
    }

    companion object {
        private fun rid(ctx: Context, name: String, type: String) = ctx.resources.getIdentifier(name, type, ctx.packageName)

        private fun cmd(ctx: Context, action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
            ctx, code, Intent(ctx, CmdReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        fun build(ctx: Context, s: SonyState): RemoteViews {
            val v = RemoteViews(ctx.packageName, rid(ctx, "widget", "layout"))
            val on = s.connected
            fun vid(n: String) = rid(ctx, n, "id")

            val pend = Store.pending
            v.setTextViewText(vid("mode"), Fmt.widgetTitle(s) + if (pend) "  …" else "")
            v.setTextColor(vid("mode"), if (on && !pend) Col.WHITE else Col.MUTED)
            v.setTextColor(vid("dot"), if (on) Col.GREEN else Col.GRAY)

            fun bud(idn: String, n: Int, chg: Boolean, last: Int) {
                v.setTextViewText(vid(idn), Fmt.bud(n, chg, last, on))
                v.setTextColor(vid(idn), if (on && n >= 0) (if (n <= 15) Col.RED else Col.WHITE) else Col.MUTED)
            }
            bud("bl", s.left, s.leftChg, s.leftLast)
            bud("br", s.right, s.rightChg, s.rightLast)
            bud("bc", s.caseBat, s.caseChg, s.caseLast)

            fun modeBtn(idn: String, key: String, onDrawable: String) {
                val active = on && s.mode == key
                v.setInt(vid(idn), "setBackgroundResource", rid(ctx, if (active) onDrawable else "seg_none", "drawable"))
                v.setTextColor(vid(idn), if (active) Col.WHITE else if (on) Col.TEXT2 else Col.MUTED)
            }
            modeBtn("b_nc", "NC", "btn_nc_on")
            modeBtn("b_amb", "AMBIENT", "btn_amb_on")
            modeBtn("b_off", "OFF", "btn_off_on")

            val amb = on && s.mode == "AMBIENT"
            val lv = s.ambLevel
            v.setTextViewText(vid("lvl"), if (pend) "…" else if (lv >= 0) "$lv" else "—")
            v.setTextColor(vid("lvl"), if (amb) Col.WHITE else Col.MUTED)
            for (i in 0 until 10) {
                val d = if (lv > i * 2) (if (amb) "seg_on" else "seg_dim") else "seg_off"
                v.setInt(vid("seg$i"), "setBackgroundResource", rid(ctx, d, "drawable"))
                v.setOnClickPendingIntent(vid("seg$i"), cmd(ctx, Act.LVL + (i + 1), 20 + i))
            }

            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            v.setOnClickPendingIntent(vid("root"), open)
            v.setOnClickPendingIntent(vid("b_nc"), cmd(ctx, Act.NC, 10))
            v.setOnClickPendingIntent(vid("b_amb"), cmd(ctx, Act.AMB, 11))
            v.setOnClickPendingIntent(vid("b_off"), cmd(ctx, Act.OFF, 12))
            return v
        }

        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, StatusWidget::class.java))
            if (ids.isNotEmpty()) mgr.updateAppWidget(ids, build(ctx, Store.state))
        }
    }
}
