package app.budswidget

// Human-readable formatting of SonyState (localized via string resources).

object Fmt {
    fun mode(s: SonyState): String = when (s.mode) {
        "NC" -> Str.get("title_nc")
        "AMBIENT" -> Str.get("title_amb") + if (s.ambLevel >= 0) " (${s.ambLevel})" else ""
        "OFF" -> Str.get("title_off")
        else -> "—"
    }

    fun bud(n: Int, chg: Boolean, last: Int, live: Boolean): String =
        if (live && n >= 0) "$n%" + (if (chg) "⚡" else "")
        else if (last >= 0) "—($last%)" else "—"

    fun batt(s: SonyState): String {
        val c = s.connected
        return "L ${bud(s.left, s.leftChg, s.leftLast, c)}   R ${bud(s.right, s.rightChg, s.rightLast, c)}   ${Str.get("bat_case")} ${bud(s.caseBat, s.caseChg, s.caseLast, c)}"
    }

    fun modeName(s: SonyState): String = when (s.mode) {
        "NC" -> Str.get("title_nc")
        "AMBIENT" -> Str.get("title_amb")
        "OFF" -> Str.get("title_off")
        else -> "—"
    }

    fun widgetTitle(s: SonyState): String = if (s.connected) modeName(s) else Str.get("title_none")

    fun title(s: SonyState): String = if (s.connected) mode(s) else Str.get("title_none")
}
