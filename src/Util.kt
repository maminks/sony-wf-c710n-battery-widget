package app.budswidget

// Small shared helpers: hex dump, string resources, colors, broadcast actions.

fun ByteArray.hex(): String = joinToString(" ") { "%02x".format(it) }

object Str {
    fun get(name: String): String {
        val c = Store.appCtx ?: return name
        val id = c.resources.getIdentifier(name, "string", c.packageName)
        return if (id != 0) c.getString(id) else name
    }
}

object Col {
    val BG = 0xFF101317.toInt()
    val CARD = 0xFF1B1F25.toInt()
    val CHIP = 0xFF242A32.toInt()
    val BTN = 0xFF2B3038.toInt()
    val WHITE = 0xFFFFFFFF.toInt()
    val TEXT2 = 0xFFB0BEC5.toInt()
    val MUTED = 0xFF78909C.toInt()
    val BLUE = 0xFF3D7BFF.toInt()
    val GREEN = 0xFF2FB67C.toInt()
    val GRAY = 0xFF6B7785.toInt()
    val RED = 0xFFFF6B6B.toInt()
}

object Act {
    const val NC = "app.budswidget.NC"
    const val AMB = "app.budswidget.AMB"
    const val OFF = "app.budswidget.OFF"
    const val LVL = "app.budswidget.LVL_"   // + 1..10 -> level n*2
}
